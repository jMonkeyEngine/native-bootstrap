package com.jme3.nativebootstrap.directories;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Optional permission helpers, with one time budget for an entire directory attempt. */
class PermissionCommands {
    private static final Logger LOG = Logger.getLogger(PermissionCommands.class.getName());
    private static final int OUTPUT_LIMIT = 64 * 1024;
    private final long budgetNanos;
    private final Set<String> unavailable = new HashSet<>();
    private long startedAt;
    private boolean started;

    PermissionCommands() {
        this(5000);
    }

    PermissionCommands(long timeoutMillis) {
        budgetNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    Result run(String program, List<String> arguments, Map<String, String> environment) throws IOException {
        checkInterrupted();
        if (unavailable.contains(program)) return null;
        List<Path> candidates;
        try {
            candidates = executables(program, System.getenv());
        } catch (SecurityException e) {
            LOG.log(Level.FINE, "Permission helper environment unavailable: " + program, e);
            unavailable.add(program);
            return null;
        }
        Result result = run(candidates, arguments, environment);
        if (result == null || result.exitCode != 0 || !result.complete) {
            unavailable.add(program);
            LOG.fine("Permission helper did not complete successfully: " + program);
        }
        return result;
    }

    Result run(List<Path> executables, List<String> arguments, Map<String, String> environment)
            throws IOException {
        checkInterrupted();
        if (!started) {
            startedAt = System.nanoTime();
            started = true;
        }
        for (Path executable : executables) {
            if (remainingNanos() <= 0) return null;
            Process process = null;
            try {
                if (!executable.isAbsolute() || !Files.isRegularFile(executable)
                        || !Files.isExecutable(executable))
                    continue;
                List<String> command = new ArrayList<>();
                command.add(executable.toString());
                command.addAll(arguments);
                ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
                builder.environment().put("LC_ALL", "C");
                builder.environment().putAll(environment);
                process = builder.start();
                final InputStream stream = process.getInputStream();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                AtomicBoolean incomplete = new AtomicBoolean();
                Thread reader = new Thread(() -> {
                    byte[] buffer = new byte[4096];
                    try (InputStream input = stream) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            int retained = Math.min(count, OUTPUT_LIMIT - output.size());
                            output.write(buffer, 0, retained);
                            if (retained < count) incomplete.set(true);
                        }
                    } catch (IOException e) {
                        incomplete.set(true);
                    }
                }, "native-directory-helper-output");
                reader.setDaemon(true);
                reader.start();
                process.getOutputStream().close();
                long remaining = remainingNanos();
                if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                    LOG.fine("Permission helper timed out: " + executable);
                    return null;
                }
                remaining = remainingNanos();
                if (remaining > 0) TimeUnit.NANOSECONDS.timedJoin(reader, remaining);
                if (reader.isAlive()) return new Result(process.exitValue(), "", false);
                return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8.name()),
                        !incomplete.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while preparing native directory");
            } catch (IOException | SecurityException | UnsupportedOperationException e) {
                LOG.log(Level.FINE, "Permission helper unavailable: " + executable, e);
            } finally {
                if (process != null) {
                    process.destroyForcibly();
                    // The daemon reader owns the input stream; closing it here could block
                    // behind a read when a descendant inherited the pipe.
                }
            }
        }
        return null;
    }

    private long remainingNanos() {
        return budgetNanos - (System.nanoTime() - startedAt);
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Interrupted while preparing native directory");
    }

    static List<Path> executables(String program, Map<String, String> environment) {
        if (program.equals("chmod") || program.equals("ls")) {
            List<Path> paths = new ArrayList<>();
            paths.add(Paths.get("/bin", program));
            paths.add(Paths.get("/usr/bin", program));
            return paths;
        }
        if (!program.equals("powershell"))
            throw new IllegalArgumentException("Unknown permission helper: " + program);
        Set<Path> paths = new LinkedHashSet<>();
        for (String variable : new String[] { "SystemRoot", "windir" }) {
            String value = environment.get(variable);
            if (value == null || value.trim().isEmpty()) continue;
            try {
                Path root = Paths.get(value);
                if (!root.isAbsolute()) continue;
                // Sysnative bypasses WOW64 redirection when called from a 32-bit JVM.
                for (String directory : new String[] { "Sysnative", "System32", "SysWOW64", "SysArm32" })
                    paths.add(root.resolve(directory).resolve("WindowsPowerShell/v1.0/powershell.exe"));
            } catch (InvalidPathException ignored) {
            }
        }
        return new ArrayList<>(paths);
    }

    static final class Result {
        final int exitCode;
        final String output;
        final boolean complete;

        Result(int exitCode, String output, boolean complete) {
            this.exitCode = exitCode;
            this.output = output;
            this.complete = complete;
        }

        boolean succeeded() {
            return exitCode == 0 && complete;
        }
    }
}
