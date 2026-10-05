package com.jme3.nativebootstrap.directories;

import com.jme3.nativebootstrap.os.OperatingSystems;
import com.jme3.nativebootstrap.common.OperatingSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeDirectoriesTest {
    @TempDir
    Path root;
    private final String[] directoryProperties = { "natives.tempDir", "natives.userHome", "natives.cacheDir",
            "natives.namespace", "java.io.tmpdir", "user.home" };
    private String[] previousProperties;

    @BeforeEach
    void isolateDirectoryProperties() {
        previousProperties = new String[directoryProperties.length];
        for (int i = 0; i < directoryProperties.length; i++) {
            previousProperties[i] = System.getProperty(directoryProperties[i]);
            if (i < 4) System.clearProperty(directoryProperties[i]);
        }
    }

    @AfterEach
    void restoreDirectoryProperties() {
        for (int i = 0; i < directoryProperties.length; i++) {
            if (previousProperties[i] == null) System.clearProperty(directoryProperties[i]);
            else System.setProperty(directoryProperties[i], previousProperties[i]);
        }
    }

    @Test
    void overridesReplaceDefaultsForEveryDesktopOsWithoutCreatingDirectories() {
        Path temp = root.resolve("temp"), home = root.resolve("home"), cache = root.resolve("cache"),
                preferred = root.resolve("preferred");
        System.setProperty("java.io.tmpdir", "invalid-default");
        System.setProperty("user.home", "invalid-default");
        System.setProperty("natives.tempDir", temp.resolve("unused/..").toString());
        System.setProperty("natives.userHome", home.toString());
        System.setProperty("natives.cacheDir", cache.toString());
        for (OperatingSystem os : Arrays.asList(OperatingSystem.WINDOWS, OperatingSystem.MACOS,
                OperatingSystem.LINUX)) {
            AtomicInteger calls = new AtomicInteger();
            List<DirectoryCandidate> candidates = NativeDirectories.candidates(".custom", "test",
                    Arrays.asList(preferred, temp), () -> {
                        calls.incrementAndGet();
                        return os;
                    });
            List<Path> paths = new ArrayList<>();
            for (DirectoryCandidate candidate : candidates) paths.add(candidate.root());
            assertEquals(Arrays.asList(preferred, temp, cache.resolve("test"),
                    home.resolve(".custom/natives/test")), paths);
            assertEquals(1, calls.get());
        }
        assertFalse(Files.exists(temp));
        assertFalse(Files.exists(home));
        assertFalse(Files.exists(cache));
        assertFalse(Files.exists(preferred));
    }

    @Test
    void explicitCacheWorksWithoutAHomeOrTempAndCreatesParentsLazily() throws IOException {
        System.clearProperty("user.home");
        System.clearProperty("java.io.tmpdir");
        Path cache = root.resolve("missing/cache");
        System.setProperty("natives.cacheDir", cache.toString());
        List<DirectoryCandidate> candidates = NativeDirectories.candidates("test", OperatingSystems::detect);
        assertEquals(1, candidates.size());
        assertEquals(cache.resolve("test"), candidates.get(0).root());
        assertFalse(Files.exists(cache));
        Path directory = candidates.get(0).get();
        assertEquals(cache.resolve("test").toRealPath(), directory.getParent());
    }

    @Test
    void homeOverrideAlsoDrivesTheDefaultMacCache() {
        Path home = root.resolve("override-home");
        System.setProperty("natives.userHome", home.toString());
        System.setProperty("user.home", root.resolve("original-home").toString());
        System.setProperty("java.io.tmpdir", root.resolve("temp").toString());
        List<DirectoryCandidate> candidates = NativeDirectories.candidates("test",
                () -> OperatingSystem.MACOS);
        assertEquals(home.resolve("Library/Caches/test"), candidates.get(1).root());
        assertEquals(home.resolve(".jme3/natives/test"), candidates.get(2).root());
    }

    @Test
    void namespaceOverrideChangesOnlyTheDefaultHomePrefix() {
        Path home = root.resolve("home");
        System.setProperty("user.home", home.toString());
        System.setProperty("java.io.tmpdir", root.resolve("temp").toString());
        System.setProperty("natives.namespace", ".app");
        List<DirectoryCandidate> defaults = NativeDirectories.candidates("test", () -> OperatingSystem.MACOS);
        assertEquals(home.resolve(".app/natives/test"), defaults.get(2).root());
        assertEquals(home.resolve("Library/Caches/test"), defaults.get(1).root());
        List<DirectoryCandidate> explicit = NativeDirectories.candidates(".explicit", "test",
                () -> OperatingSystem.MACOS);
        assertEquals(home.resolve(".explicit/natives/test"), explicit.get(2).root());
        System.setProperty("natives.namespace", "  ");
        assertEquals(home.resolve(".jme3/natives/test"),
                NativeDirectories.candidates("test", () -> OperatingSystem.MACOS).get(2).root());
        System.setProperty("natives.namespace", "../escape");
        assertThrows(IllegalArgumentException.class,
                () -> NativeDirectories.candidates("test", OperatingSystems::detect));
        assertEquals(home.resolve(".explicit/natives/test"),
                NativeDirectories.candidates(".explicit", "test", () -> OperatingSystem.MACOS).get(2).root());
    }

    @Test
    void blankOverridesUseDefaultsAndRelativeOverridesAreRejected() {
        Path home = root.resolve("home"), temp = root.resolve("temp");
        System.setProperty("user.home", home.toString());
        System.setProperty("java.io.tmpdir", temp.toString());
        for (String property : Arrays.asList("natives.tempDir", "natives.userHome", "natives.cacheDir"))
            System.setProperty(property, "  ");
        List<DirectoryCandidate> candidates = NativeDirectories.candidates("test",
                () -> OperatingSystem.MACOS);
        assertEquals(temp, candidates.get(0).root());
        assertEquals(home.resolve("Library/Caches/test"), candidates.get(1).root());
        for (String property : Arrays.asList("natives.tempDir", "natives.userHome", "natives.cacheDir")) {
            System.setProperty(property, "relative/path");
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> NativeDirectories.candidates("test", OperatingSystems::detect)).getMessage()
                    .contains(property));
            assertEquals(root, NativeDirectories
                    .fromRoots("test", Arrays.asList(root), OperatingSystems::detect).get(0).root());
            System.clearProperty(property);
        }
    }

    @Test
    void discoveryIsLazyOrderedAndDeduplicated() {
        Path a = root.resolve("a"), b = root.resolve("b");
        List<DirectoryCandidate> candidates = NativeDirectories.fromRoots("test", Arrays.asList(a, b, a),
                OperatingSystems::detect);
        assertEquals(Arrays.asList(a, b), Arrays.asList(candidates.get(0).root(), candidates.get(1).root()));
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
        assertThrows(UnsupportedOperationException.class, () -> candidates.clear());
    }

    @Test
    void defaultAndCustomHomePrefixesPreserveCandidateOrderAndLazyCreation() throws IOException {
        String previousHome = System.getProperty("user.home");
        String previousTemp = System.getProperty("java.io.tmpdir");
        Path home = root.resolve("home"), temp = root.resolve("temp"), preferred = root.resolve("preferred");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("java.io.tmpdir", temp.toString());
            List<DirectoryCandidate> defaults = NativeDirectories.candidates("test",
                    () -> OperatingSystem.MACOS);
            assertEquals(
                    Arrays.asList(temp, home.resolve("Library/Caches/test"),
                            home.resolve(".jme3/natives/test")),
                    Arrays.asList(defaults.get(0).root(), defaults.get(1).root(), defaults.get(2).root()));
            List<DirectoryCandidate> custom = NativeDirectories.candidates(".my-app", "test",
                    () -> OperatingSystem.MACOS);
            assertEquals(defaults.get(0).root(), custom.get(0).root());
            assertEquals(defaults.get(1).root(), custom.get(1).root());
            assertEquals(home.resolve(".my-app/natives/test"), custom.get(2).root());
            List<DirectoryCandidate> withPreferred = NativeDirectories.candidates(".my-app", "test",
                    Arrays.asList(preferred, temp, preferred), OperatingSystems::detect);
            assertEquals(preferred, withPreferred.get(0).root());
            assertEquals(temp, withPreferred.get(1).root());
            DirectoryCandidate fallback = withPreferred.get(withPreferred.size() - 1);
            assertEquals(home.resolve(".my-app/natives/test"), fallback.root());
            assertFalse(Files.exists(home));
            assertFalse(Files.exists(temp));
            assertFalse(Files.exists(preferred));
            Path directory = fallback.get();
            assertEquals(fallback.root().toRealPath(), directory.getParent());
            assertTrue(directory.getFileName().toString().startsWith("test-"));
        } finally {
            System.setProperty("user.home", previousHome);
            System.setProperty("java.io.tmpdir", previousTemp);
        }
    }

    @Test
    void rejectsHomePrefixesThatEscapeTheUsersHome() {
        for (String prefix : new String[] { null, "", ".", "..", "../escape", "/tmp", "C:\\temp",
                "nested/path", "nested\\path" }) {
            assertThrows(IllegalArgumentException.class,
                    () -> NativeDirectories.candidates(prefix, "test", OperatingSystems::detect));
        }
    }

    @Test
    void usesInjectedOsWithoutDetectingTheHostAndSnapshotsItOnce() throws IOException {
        String previousOs = System.getProperty("os.name");
        String previousHome = System.getProperty("user.home");
        String previousTemp = System.getProperty("java.io.tmpdir");
        AtomicInteger calls = new AtomicInteger();
        OperatingSystem actualHost = OperatingSystems.detect();
        try {
            // A Darwin detector result must select the macOS cache even with a conflicting host label.
            System.setProperty("os.name", "Windows 11");
            System.setProperty("user.home", root.resolve("home").toString());
            System.setProperty("java.io.tmpdir", root.resolve("temp").toString());
            List<DirectoryCandidate> candidates = NativeDirectories.candidates("injected", () -> {
                calls.incrementAndGet();
                return com.jme3.nativebootstrap.common.OperatingSystem.MACOS;
            });
            assertEquals(root.resolve("home/Library/Caches/injected"), candidates.get(1).root());
            assertEquals(1, calls.get());
            assertFalse(Files.exists(candidates.get(1).root()));
        } finally {
            System.setProperty("os.name", previousOs);
            System.setProperty("user.home", previousHome);
            System.setProperty("java.io.tmpdir", previousTemp);
        }
        calls.set(0);
        DirectoryCandidate candidate = NativeDirectories.fromRoots("snapshot", Arrays.asList(root), () -> {
            if (calls.incrementAndGet() != 1)
                throw new AssertionError("OS supplier must not be called again");
            return actualHost;
        }).get(0);
        Path first = candidate.get(), second = candidate.get();
        assertNotEquals(first, second);
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsUnknownOsBeforeCreatingDirectories() {
        Path missing = root.resolve("unknown");
        DirectoryCandidate candidate = NativeDirectories.fromRoots("test", Arrays.asList(missing),
                () -> com.jme3.nativebootstrap.common.OperatingSystem.UNKNOWN).get(0);
        assertThrows(UncheckedIOException.class, candidate::get);
        assertFalse(Files.exists(missing));
        assertThrows(NullPointerException.class, () -> NativeDirectories.candidates("test", () -> null));
    }

    @Test
    void createsParentsAndFreshPrivateDirectories() throws IOException {
        DirectoryCandidate candidate = NativeDirectories
                .fromRoots("test", Arrays.asList(root.resolve("missing/parents")), OperatingSystems::detect)
                .get(0);
        Path a = candidate.get(), b = candidate.get();
        assertNotEquals(a, b);
        assertEquals(candidate.root().toRealPath(), a.getParent());
        if (Files.getFileAttributeView(a, PosixFileAttributeView.class) != null)
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(a));
    }

    @Test
    void doesNotDependOnUserNameLookup() {
        String previous = System.getProperty("user.name");
        try {
            System.setProperty("user.name", "?");
            assertTrue(Files.isDirectory(NativeDirectories
                    .fromRoots("test", Arrays.asList(root), OperatingSystems::detect).get(0).get()));
        } finally {
            System.setProperty("user.name", previous);
        }
    }

    @Test
    void concurrentCallsProduceDistinctDirectories() throws Exception {
        DirectoryCandidate candidate = NativeDirectories
                .fromRoots("test", Arrays.asList(root), OperatingSystems::detect).get(0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Path> a = executor.submit(candidate::get);
            Future<Path> b = executor.submit(candidate::get);
            assertNotEquals(a.get(), b.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsFilesAsRoots() throws IOException {
        Path file = Files.write(root.resolve("file"), "data".getBytes(StandardCharsets.UTF_8));
        assertThrows(UncheckedIOException.class, () -> NativeDirectories
                .fromRoots("test", Arrays.asList(file), OperatingSystems::detect).get(0).get());
    }

    @Test
    void rejectsPathTraversalNamespaces() {
        assertThrows(IllegalArgumentException.class,
                () -> NativeDirectories.candidates("../escape", OperatingSystems::detect));
        assertThrows(IllegalArgumentException.class,
                () -> NativeDirectories.candidates("", OperatingSystems::detect));
    }

    @Test
    void resolvesSymlinkAliases() throws IOException {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        Path real = Files.createDirectory(root.resolve("real"));
        Path alias = Files.createSymbolicLink(root.resolve("alias"), real);
        Path created = NativeDirectories.fromRoots("test", Arrays.asList(alias), OperatingSystems::detect)
                .get(0).get();
        assertEquals(real.toRealPath(), created.getParent());
    }

    @Test
    void rejectsWorldWritableNonStickyAncestor() throws IOException {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        Path shared = Files.createTempDirectory(Paths.get("/tmp"), "native-bootstrap-unsafe-");
        try {
            Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
            assertThrows(UncheckedIOException.class, () -> NativeDirectories
                    .fromRoots("test", Arrays.asList(shared), OperatingSystems::detect).get(0).get());
        } finally {
            Files.delete(shared);
        }
    }

    @Test
    void rejectsModifyingInheritedMacAcl() throws Exception {
        assumeTrue(OperatingSystems.detect() == com.jme3.nativebootstrap.common.OperatingSystem.MACOS);
        assertEquals(0, new ProcessBuilder("/bin/chmod", "+a",
                "everyone allow list,search,add_file,add_subdirectory,delete_child,file_inherit,directory_inherit",
                root.toString()).start().waitFor());
        assertThrows(UncheckedIOException.class, () -> NativeDirectories
                .fromRoots("test", Arrays.asList(root), OperatingSystems::detect).get(0).get());
        try (Stream<Path> entries = Files.list(root)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void rejectsWritableChildBehindMacTraversalAcl() throws Exception {
        assumeTrue(OperatingSystems.detect() == com.jme3.nativebootstrap.common.OperatingSystem.MACOS);
        Path parent = Files.createTempDirectory(Paths.get("/tmp"), "native-bootstrap-traversal-");
        Path child = parent.resolve("child");
        try {
            assertEquals(0, new ProcessBuilder("/bin/chmod", "+a", "everyone allow search", parent.toString())
                    .start().waitFor());
            Files.createDirectory(child);
            Files.setPosixFilePermissions(child, PosixFilePermissions.fromString("rwxrwxrwx"));
            assertThrows(UncheckedIOException.class, () -> NativeDirectories
                    .fromRoots("test", Arrays.asList(child), OperatingSystems::detect).get(0).get());
        } finally {
            Files.deleteIfExists(child);
            Files.delete(parent);
        }
    }

    @Test
    void rejectsNoexecWhenAnExplicitFixtureIsProvided() {
        String fixture = System.getenv("NATIVE_BOOTSTRAP_NOEXEC_ROOT");
        assumeTrue(fixture != null);
        assertThrows(UncheckedIOException.class, () -> NativeDirectories
                .fromRoots("test", Arrays.asList(Paths.get(fixture)), OperatingSystems::detect).get(0).get());
    }

    @Test
    void doesNotConsultMountMetadata() throws Exception {
        String executable = OperatingSystems
                .detect() == com.jme3.nativebootstrap.common.OperatingSystem.WINDOWS ? "java.exe" : "java";
        String classpath = System.getProperty("test.classpath");
        Path log = root.resolve("no-filestore.log");
        ArrayList<String> command = new ArrayList<String>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", executable).toString());
        if (Double.parseDouble(System.getProperty("java.specification.version")) >= 18)
            command.add("-Djava.security.manager=allow");
        command.addAll(Arrays.asList("-cp", classpath, NoStoreProbe.class.getName(), root.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    public static class NoStoreProbe {
        public static void main(String[] args) throws Exception {
            System.setSecurityManager(new SecurityManager() {
                @Override
                public void checkPermission(java.security.Permission permission) {
                    if (permission.getName().equals("getFileStoreAttributes"))
                        throw new SecurityException("Mount metadata unavailable");
                }
            });
            Path directory = NativeDirectories
                    .fromRoots("no-filestore", Arrays.asList(Paths.get(args[0])), OperatingSystems::detect)
                    .get(0).get();
            Files.delete(directory);
        }
    }

}
