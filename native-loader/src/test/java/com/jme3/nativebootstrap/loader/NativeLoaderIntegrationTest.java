package com.jme3.nativebootstrap.loader;

import com.jme3.nativebootstrap.os.OperatingSystems;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jme3.nativebootstrap.directories.DirectoryCandidate;
import com.jme3.nativebootstrap.directories.NativeDirectories;
import java.io.BufferedReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeLoaderIntegrationTest {
    @TempDir
    Path root;

    private static native int answer();

    private static native int loadCount();

    @Test
    void loadsRealJniFromOsWhenTheClasspathResourceIsMissing() throws Exception {
        Path base = Files.createDirectory(root.resolve("missing-resource"));
        Path libraries = installOsProbe(base);
        runProbe(base, "os-missing", new String[] { "-Djava.library.path=" + libraries });
    }

    @Test
    void prefersRealOsJniWithoutExtractingTheClasspathResource() throws Exception {
        Path base = Files.createDirectory(root.resolve("os-preferred"));
        Path libraries = installOsProbe(base);
        runProbe(base, "os-preferred",
                new String[] { "-Djava.library.path=" + libraries, "-Dnatives.preferOsLibraries=true" });
    }

    @Test
    void androidBranchLoadsRealOsJniWithoutExtracting() throws Exception {
        Path base = Files.createDirectory(root.resolve("android-os"));
        Path libraries = installOsProbe(base);
        runProbe(base, "os-android",
                new String[] { "-Djava.library.path=" + libraries, "-Dnatives.withOsLibraries=false" });
    }

    @Test
    void loadsRealOsJniWhenNoExtractionDirectoryWorks() throws Exception {
        Path base = Files.createDirectory(root.resolve("os-no-directory"));
        Path libraries = installOsProbe(base);
        runProbe(base, "os-no-directory", new String[] { "-Djava.library.path=" + libraries });
    }

    @Test
    void neverReloadsRealOsJniAfterPartialSuccess() throws Exception {
        Path base = Files.createDirectory(root.resolve("os-partial"));
        Path libraries = installOsProbe(base);
        runProbe(base, "os-partial",
                new String[] { "-Djava.library.path=" + libraries, "-Dnatives.preferOsLibraries=true" });
    }

    @Test
    void extractsRealJniWhenThePreferredOsLibraryIsUnavailable() throws Exception {
        Path base = Files.createDirectory(root.resolve("os-unavailable"));
        runProbe(base, "os-unavailable",
                new String[] { "-Djava.library.path=" + base, "-Dnatives.preferOsLibraries=true" });
    }

    @Test
    void loadsRealOsJniWhenTheExtractedBinaryCannotLoad() throws Exception {
        Path base = Files.createDirectory(root.resolve("invalid-classpath-binary"));
        Path libraries = installOsProbe(base);
        Files.move(libraries.resolve(System.mapLibraryName("nativebootstrap_probe")),
                libraries.resolve(System.mapLibraryName("second.bin")));
        runProbe(base, "os-load-fallback", new String[] { "-Djava.library.path=" + libraries });
    }

    private Path installOsProbe(Path base) throws Exception {
        Path libraries = Files.createDirectory(base.resolve("os-libraries"));
        NativeResource resource = NativeResource.fromClasspath(getClass(),
                "/native/" + System.mapLibraryName("nativebootstrap_probe"));
        try (java.io.InputStream input = resource.open()) {
            Files.copy(input, libraries.resolve(resource.fileName()));
        }
        return libraries;
    }

    @Test
    void preservesRealJniAfterPartialLoadWithoutRetrying() throws Exception {
        runProbe(Files.createDirectory(root.resolve("partial-load")), "partial-load", null);
    }

    @Test
    void loadsRealJniOnTheCurrentPlatform() throws Exception {
        runProbe(Files.createDirectory(root.resolve("extraction")), null, null);
    }

    @Test
    void readmeExampleExtractsAndLoadsRealJni() throws Exception {
        Path base = Files.createDirectory(root.resolve("readme"));
        runProbe(base, "readme", new String[] { "-Dnatives.tempDir=" + base.resolve("natives"),
                "-Dnatives.withOsLibraries=false" });
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
            Path expected = base.resolve(destination).resolve(".custom/jni-test");
            runProbe(base, destination, new String[] {
                    "-Dnatives.tempDir=" + (destination.equals("temp") ? base.resolve("temp") : blocked),
                    "-Dnatives.cacheDir=" + (destination.equals("home") ? blocked : base.resolve("cache")),
                    "-Dnatives.userHome=" + base.resolve("home"), "-Dnatives.namespace=.custom",
                    "-Dprobe.expectedRoot=" + expected });
        }
    }

    @Test
    void loadsRealJniInLinuxUserNamespaceWithUnmappedOwners() throws Exception {
        assumeTrue("true".equals(System.getenv("NATIVE_BOOTSTRAP_TEST_BUBBLEWRAP")));
        Path hostTmp = Paths.get("/tmp");
        Path home = Paths.get("/host-home").resolve(hostTmp.relativize(root.toRealPath()));
        List<String> sandbox = Arrays.asList("bwrap", "--unshare-user", "--uid", "1000", "--gid", "1000",
                "--die-with-parent", "--ro-bind", "/usr", "/usr", "--ro-bind", "/etc", "/etc", "--ro-bind",
                "/home", "/home", "--ro-bind-try", "/opt", "/opt", "--ro-bind-try", "/var", "/var",
                "--symlink", "usr/bin", "/bin", "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib64",
                "/lib64", "--proc", "/proc", "--dev", "/dev", "--bind", "/tmp", "/tmp", "--bind", "/tmp",
                "/host-home", "--perms", "01777", "--tmpfs", "/dev/shm", "--perms", "0700", "--dir",
                "/runtime", "--setenv", "XDG_RUNTIME_DIR");
        for (String runtime : Arrays.asList("/runtime", "/runtime/missing")) {
            List<String> command = new java.util.ArrayList<>(sandbox);
            command.addAll(Arrays.asList(runtime, "--chdir", "/", "--"));
            String expectedRoot = (runtime.equals("/runtime") ? runtime : "/dev/shm") + "/.jme3/jni-test";
            runProbe(root, "linux-namespace",
                    new String[] { "-Djava.io.tmpdir=" + root.toRealPath(), "-Dnatives.userHome=" + home,
                            "-Dnatives.cacheDir=" + home.resolve("cache"), "-Dnatives.withOsLibraries=false",
                            "-Dprobe.expectedRoot=" + expectedRoot },
                    command);
        }
    }

    private void runProbe(Path extraction, String mode, String[] options) throws Exception {
        runProbe(extraction, mode, options, Collections.emptyList());
    }

    private void runProbe(Path extraction, String mode, String[] options, List<String> prefix)
            throws Exception {
        Path log = extraction.resolve("smoke.log");
        String executable = OperatingSystems
                .detect() == com.jme3.nativebootstrap.common.OperatingSystem.WINDOWS ? "java.exe" : "java";
        List<String> command = new java.util.ArrayList<>(prefix);
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
            if (args.length > 1 && args[1].equals("linux-namespace")) verifyUnmappedRoots();
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
            if (args.length > 1 && args[1].equals("os-partial")) {
                UnsatisfiedLinkError fileFailure = new UnsatisfiedLinkError("Invalid second binary");
                try {
                    new NativeLoader(path -> {
                        throw fileFailure;
                    }).load(Arrays.asList(() -> NativeDirectories.fromRoots("jni-test",
                            Arrays.asList(Paths.get(args[0])), OperatingSystems::detect).get(0).get(), () -> {
                                throw new AssertionError("Must not retry a partial OS load");
                            }), Arrays.asList(resource,
                                    NativeResource.fromClasspath(Probe.class, "/native/second.bin")));
                    throw new AssertionError("Second binary should fail");
                } catch (UnsatisfiedLinkError error) {
                    if (!error.getMessage().contains("OS library nativebootstrap_probe")
                            || error.getSuppressed().length != 2
                            || error.getSuppressed()[1].getCause() != fileFailure)
                        throw new AssertionError("Missing partial OS load diagnostics", error);
                }
                if (loadCount() != 1) throw new AssertionError("JNI initialized more than once");
                System.out.println("NATIVE_OK " + answer());
                return;
            }
            if (args.length > 1 && args[1].equals("readme")) {
                Path loaded = new NativeLoader(path -> System.load(path.toString())).load(
                        NativeDirectories.candidates("jni-test", OperatingSystems::detect),
                        Arrays.asList(resource));
                Path expected = Paths.get(System.getProperty("natives.tempDir"), ".jme3", "jni-test")
                        .toRealPath();
                if (loaded == null || !loaded.getParent().equals(expected)
                        || !Files.isRegularFile(loaded.resolve(resource.fileName())) || loadCount() != 1)
                    throw new AssertionError("README extraction or JNI initialization failed: " + loaded);
                System.out.println("NATIVE_OK " + answer());
                return;
            }
            if (args.length > 1 && args[1].startsWith("os-")) {
                if (args[1].equals("os-android")) System.setProperty("java.runtime.name", "Android Runtime");
                boolean extracts = args[1].equals("os-unavailable") || args[1].equals("os-load-fallback");
                if (args[1].equals("os-missing")) resource = NativeResource.fromClasspath(Probe.class,
                        "/not-in-jar/" + System.mapLibraryName("nativebootstrap_probe"));
                if (args[1].equals("os-load-fallback"))
                    resource = NativeResource.fromClasspath(Probe.class, "/native/second.bin");
                java.util.List<? extends java.util.function.Supplier<Path>> candidates = args[1]
                        .equals("os-android") ? NativeDirectories.fromRoots("jni-test", Arrays.asList(Paths.get(args[0])), OperatingSystems::detect) : Arrays.asList(() -> {
                            if (args[1].equals("os-no-directory")) throw new java.io.UncheckedIOException(
                                    new java.io.IOException("Unwritable root"));
                            if (!extracts) throw new AssertionError("OS loading should not extract files");
                            return NativeDirectories.fromRoots("jni-test", Arrays.asList(Paths.get(args[0])),
                                    OperatingSystems::detect).get(0).get();
                        });
                Path loaded = new NativeLoader(p -> System.load(p.toString())).load(candidates,
                        Arrays.asList(resource));
                if (extracts ? loaded == null : loaded != null)
                    throw new AssertionError("Unexpected extraction directory: " + loaded);
                if (loadCount() != 1) throw new AssertionError("JNI initialized more than once");
                System.out.println("NATIVE_OK " + answer());
                return;
            }
            if (args.length > 1 && args[1].equals("partial-load")) {
                Path[] directory = new Path[1];
                java.util.List<String> linked = new java.util.ArrayList<>();
                try {
                    new NativeLoader(p -> {
                        linked.add(p.getFileName().toString());
                        System.load(p.toString());
                    }).load(Arrays.asList(() -> {
                        directory[0] = NativeDirectories.fromRoots("jni-test",
                                Arrays.asList(Paths.get(args[0])), OperatingSystems::detect).get(0).get();
                        return directory[0];
                    }, () -> {
                        throw new AssertionError("Must not retry a partial native load");
                    }), Arrays.asList(resource,
                            NativeResource.fromClasspath(Probe.class, "/native/second.bin")));
                    throw new AssertionError("Invalid second binary should fail to load");
                } catch (UnsatisfiedLinkError error) {
                    if (!error.getMessage().contains("fallback stopped") || error.getSuppressed().length != 1)
                        throw new AssertionError("Missing partial-load diagnostics", error);
                }
                if (!linked.equals(Arrays.asList(resource.fileName(), "second.bin")) || loadCount() != 1)
                    throw new AssertionError("Unexpected native load count: " + linked);
                if (!Files.isRegularFile(directory[0].resolve(resource.fileName()))
                        || !Files.isRegularFile(directory[0].resolve("second.bin")))
                    throw new AssertionError("Partial extraction was removed");
                System.out.println("NATIVE_OK " + answer());
                return;
            }
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
            if (args.length > 1 && args[1].equals("linux-namespace")
                    && Files.exists(Paths.get("/runtime/missing")))
                throw new AssertionError("A missing runtime directory was created");
            System.out.println("NATIVE_OK " + answer());
        }

        private static void verifyUnmappedRoots() throws Exception {
            String overflowUid;
            try (BufferedReader reader = Files.newBufferedReader(Paths.get("/proc/sys/kernel/overflowuid"))) {
                overflowUid = reader.readLine().trim();
            }
            List<DirectoryCandidate> candidates = NativeDirectories.candidates("jni-test",
                    OperatingSystems::detect);
            Path runtime = Paths.get(System.getenv("XDG_RUNTIME_DIR"));
            boolean runtimeCandidate = candidates.stream()
                    .anyMatch(c -> c.root().equals(runtime.resolve(".jme3/jni-test")));
            if (runtimeCandidate != Files.isDirectory(runtime) || Files.exists(runtime.resolve(".jme3")))
                throw new AssertionError("Unexpected runtime candidate or eager creation: " + runtime);
            for (int index = 0; index < 3; index++) {
                Path ancestor = Paths.get(index == 0 ? "/tmp" : "/host-home");
                String owner = Files.getAttribute(ancestor, "unix:uid").toString();
                if (!overflowUid.equals(owner)) throw new AssertionError(
                        "Expected unmapped owner " + overflowUid + " for " + ancestor + " but saw " + owner);
                DirectoryCandidate candidate = candidates.get(index);
                try {
                    candidate.get();
                    throw new AssertionError("Accepted " + candidate.root() + " with an unmapped ancestor");
                } catch (UncheckedIOException failure) {
                    if (!("Untrusted directory owner: " + ancestor).equals(failure.getCause().getMessage()))
                        throw failure;
                }
            }
        }
    }
}
