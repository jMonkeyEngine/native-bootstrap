package com.jme3.nativebootstrap.loader;

import com.jme3.nativebootstrap.os.OperatingSystems;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jme3.nativebootstrap.directories.NativeDirectories;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class NativeLoaderIntegrationTest {
    @TempDir
    Path root;

    private static native int answer();

    @Test
    void loadsRealJniOnTheCurrentPlatform() throws Exception {
        runProbe(Files.createDirectory(root.resolve("extraction")), null, null);
    }

    @Test
    void loadsRealJniWhenPermissionHelperProcessesAreDenied() throws Exception {
        String[] options = Double.parseDouble(System.getProperty(
                "java.specification.version")) >= 18 ? new String[] { "-Djava.security.manager=allow" }
                                                     : null;
        runProbe(Files.createDirectory(root.resolve("without-helpers")), "without-helpers", options);
    }

    @Test
    void loadsRealJniWithAdditionalChecksDisabledWithoutAttemptingHelpers() throws Exception {
        String[] options = Double.parseDouble(System.getProperty(
                "java.specification.version")) >= 18 ? new String[] { "-Dnatives.noAdditionalChecks=true",
                        "-Djava.security.manager=allow" }
                                                     : new String[] { "-Dnatives.noAdditionalChecks=true" };
        runProbe(Files.createDirectory(root.resolve("disabled-helpers")), "disabled-helpers", options);
    }

    @Test
    void loadsRealJniUsingPropertyOverridesAndFallbacks() throws Exception {
        Path blocked = Files.write(root.resolve("blocked"), new byte[] { 1 });
        for (String destination : Arrays.asList("temp", "cache", "home")) {
            Path base = Files.createDirectory(root.resolve(destination));
            Path expected = destination.equals(
                    "temp") ? base.resolve("temp")
                            : destination.equals("cache") ? base.resolve("cache/jni-test")
                                                          : base.resolve("home/.custom/natives/jni-test");
            runProbe(base, destination, new String[] {
                    "-Dnatives.tempDir=" + (destination.equals("temp") ? base.resolve("temp") : blocked),
                    "-Dnatives.cacheDir=" + (destination.equals("home") ? blocked : base.resolve("cache")),
                    "-Dnatives.userHome=" + base.resolve("home"), "-Dnatives.namespace=.custom",
                    "-Dprobe.expectedRoot=" + expected });
        }
    }

    private void runProbe(Path extraction, String mode, String[] options) throws Exception {
        Path log = extraction.resolve("smoke.log");
        String executable = OperatingSystems
                .detect() == com.jme3.nativebootstrap.common.OperatingSystem.WINDOWS ? "java.exe" : "java";
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", executable).toString());
        if (options != null) command.addAll(Arrays.asList(options));
        command.addAll(Arrays.asList("-cp", System.getProperty("test.classpath"), Probe.class.getName(),
                extraction.toString()));
        if (mode != null) command.add(mode);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Native smoke process timed out");
            String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("NATIVE_OK 42"), output);
        } finally {
            process.destroyForcibly();
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            if (args.length > 1 && args[1].equals("without-helpers")) {
                System.setSecurityManager(new SecurityManager() {
                    @Override
                    public void checkPermission(java.security.Permission permission) {
                    }

                    @Override
                    public void checkExec(String command) {
                        throw new SecurityException("Permission helper processes denied");
                    }
                });
            }
            if (args.length > 1 && args[1].equals("disabled-helpers")) {
                System.setSecurityManager(new SecurityManager() {
                    @Override
                    public void checkPermission(java.security.Permission permission) {
                    }

                    @Override
                    public void checkExec(String command) {
                        String name = Paths.get(command).getFileName().toString();
                        if (name.equals("chmod") || name.equals("ls")
                                || name.equalsIgnoreCase("powershell.exe"))
                            throw new AssertionError("Disabled helper was attempted: " + command);
                    }
                });
            }
            NativeResource resource = NativeResource.fromClasspath(Probe.class,
                    "/native/" + System.mapLibraryName("nativebootstrap_probe"));
            boolean useDefaultCandidates = args.length > 1 && !args[1].equals("without-helpers")
                    && !args[1].equals("disabled-helpers");
            Path loaded = new NativeLoader(p -> System.load(p.toString())).load(
                    useDefaultCandidates ? NativeDirectories.candidates("jni-test", OperatingSystems::detect)
                                         : NativeDirectories.fromRoots("jni-test",
                                                 Arrays.asList(Paths.get(args[0])), OperatingSystems::detect),
                    Arrays.asList(resource));
            String expected = System.getProperty("probe.expectedRoot");
            if (expected != null && !loaded.getParent().equals(Paths.get(expected).toRealPath()))
                throw new AssertionError("Unexpected extraction path: " + loaded);
            System.out.println("NATIVE_OK " + answer());
        }
    }
}
