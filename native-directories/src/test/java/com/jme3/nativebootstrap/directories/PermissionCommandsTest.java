package com.jme3.nativebootstrap.directories;

import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PermissionCommandsTest {
    @TempDir
    Path root;

    @Test
    void missingExecutableFallsThroughToAnAvailableAbsolutePath() throws Exception {
        PermissionCommands.Result result = new PermissionCommands().run(
                Arrays.asList(root.resolve("missing"), javaExecutable()), arguments("ok"),
                Collections.emptyMap());
        assertNotNull(result);
        assertTrue(result.succeeded());
        assertEquals("COMMAND_OK", result.output.trim());
    }

    @Test
    void missingExecutableIsOptional() throws Exception {
        assertNull(new PermissionCommands().run(Collections.singletonList(root.resolve("missing")),
                Collections.emptyList(), Collections.emptyMap()));
    }

    @Test
    void timeoutIsBoundedAndExhaustsTheSharedBudget() throws Exception {
        PermissionCommands commands = new PermissionCommands(500);
        long started = System.nanoTime();
        assertNull(commands.run(Collections.singletonList(javaExecutable()), arguments("sleep"),
                Collections.emptyMap()));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000);
        assertNull(commands.run(Collections.singletonList(javaExecutable()), arguments("ok"),
                Collections.emptyMap()));
    }

    @Test
    void excessiveOutputIsDrainedButRetainedOutputIsBounded() throws Exception {
        PermissionCommands.Result result = new PermissionCommands()
                .run(Collections.singletonList(javaExecutable()), arguments("large"), Collections.emptyMap());
        assertNotNull(result);
        assertEquals(0, result.exitCode);
        assertFalse(result.complete);
        assertEquals(64 * 1024, result.output.length());
    }

    @Test
    void inheritedOutputPipeCannotBlockPastTheWaitBudget() throws Exception {
        long started = System.nanoTime();
        PermissionCommands.Result result = new PermissionCommands(2000).run(
                Collections.singletonList(javaExecutable()), arguments("inherited-pipe"),
                Collections.emptyMap());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000);
        if (result != null) assertFalse(result.complete);
    }

    @Test
    void exitStatusPreservesConfirmedSecurityRejections() throws Exception {
        PermissionCommands.Result result = new PermissionCommands().run(
                Collections.singletonList(javaExecutable()), arguments("unsafe"), Collections.emptyMap());
        assertNotNull(result);
        assertEquals(42, result.exitCode);
        assertFalse(result.succeeded());
    }

    @Test
    void interruptionIsPropagatedAndPreserved() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> new PermissionCommands().run(Collections.singletonList(javaExecutable()),
                            arguments("ok"), Collections.emptyMap()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void windowsCandidatesSupportAlternateRootsAndSystemDirectoryVariants() {
        List<Path> candidates = PermissionCommands.executables("powershell",
                Collections.singletonMap("windir", root.toString()));
        assertEquals(Arrays.asList(root.resolve("Sysnative/WindowsPowerShell/v1.0/powershell.exe"),
                root.resolve("System32/WindowsPowerShell/v1.0/powershell.exe"),
                root.resolve("SysWOW64/WindowsPowerShell/v1.0/powershell.exe"),
                root.resolve("SysArm32/WindowsPowerShell/v1.0/powershell.exe")), candidates);
        assertTrue(PermissionCommands
                .executables("powershell", Collections.singletonMap("SystemRoot", "relative")).isEmpty());
    }

    private static Path javaExecutable() {
        return Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    }

    private static List<String> arguments(String mode) {
        return Arrays.asList("-cp", System.getProperty("test.classpath"), Probe.class.getName(), mode);
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("sleep")) Thread.sleep(30000);
            else if (args[0].equals("hold-pipe")) Thread.sleep(3500);
            else if (args[0].equals("inherited-pipe")) {
                new ProcessBuilder(javaExecutable().toString(), "-cp", System.getProperty("java.class.path"),
                        Probe.class.getName(), "hold-pipe").inheritIO().start();
            } else if (args[0].equals("large")) {
                byte[] bytes = new byte[4096];
                Arrays.fill(bytes, (byte) 'x');
                for (int i = 0; i < 128; i++) System.out.write(bytes);
            } else if (args[0].equals("unsafe")) System.exit(42);
            else System.out.println("COMMAND_OK");
        }
    }
}
