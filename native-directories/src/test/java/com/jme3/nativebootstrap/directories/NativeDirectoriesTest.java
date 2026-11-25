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
            List<Path> expected = Arrays.asList(preferred.resolve(".custom/test"),
                    temp.resolve(".custom/test"), cache.resolve(".custom/test"),
                    home.resolve(".custom/test"));
            if (os == OperatingSystem.LINUX) {
                assertEquals(expected, paths.subList(0, expected.size()));
                Path sharedMemory = Paths.get("/dev/shm");
                assertEquals(sharedMemory.isAbsolute() && Files.isDirectory(sharedMemory),
                        paths.contains(sharedMemory.resolve(".custom/test")));
            } else {
                assertEquals(expected, paths);
            }
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
        assertEquals(cache.resolve(".jme3/test"), candidates.get(0).root());
        assertFalse(Files.exists(cache));
        Path directory = candidates.get(0).get();
        assertEquals(cache.resolve(".jme3/test").toRealPath(), directory.getParent());
    }

    @Test
    void mobileCandidatesReturnOnlyAVirtualCandidateEvenWithExplicitRoots() {
        System.clearProperty("user.home");
        System.clearProperty("java.io.tmpdir");
        for (OperatingSystem os : Arrays.asList(OperatingSystem.ANDROID, OperatingSystem.IOS)) {
            List<DirectoryCandidate> candidates = NativeDirectories.candidates("test", () -> os);
            assertEquals(1, candidates.size());
            DirectoryCandidate virtual = candidates.get(0);
            assertEquals(os, virtual.operatingSystem());
            assertThrows(UnsupportedOperationException.class, virtual::root);
            assertThrows(UncheckedIOException.class, virtual::get);
            assertTrue(virtual.toString().contains(os.toString()));
            assertEquals(1,
                    NativeDirectories.fromRoots("test", java.util.Collections.emptyList(), () -> os).size());
            List<DirectoryCandidate> withRoot = NativeDirectories.fromRoots("test", Arrays.asList(root),
                    () -> os);
            assertEquals(1, withRoot.size());
            assertThrows(UnsupportedOperationException.class, withRoot.get(0)::root);
        }
        assertTrue(NativeDirectories
                .fromRoots("test", java.util.Collections.emptyList(), () -> OperatingSystem.LINUX).isEmpty());
    }

    @Test
    void mobileCandidatesDoNotResolveExtractionPathProperties() {
        System.setProperty("natives.namespace", "invalid/prefix");
        for (String property : Arrays.asList("natives.tempDir", "natives.userHome", "natives.cacheDir"))
            System.setProperty(property, "relative-path");
        for (OperatingSystem os : Arrays.asList(OperatingSystem.ANDROID, OperatingSystem.IOS)) {
            List<DirectoryCandidate> candidates = NativeDirectories.candidates("test", Arrays.asList(root),
                    () -> os);
            assertEquals(1, candidates.size());
            assertEquals(os, candidates.get(0).operatingSystem());
            assertThrows(UnsupportedOperationException.class, candidates.get(0)::root);
            assertThrows(UnsupportedOperationException.class,
                    NativeDirectories.fromRoots("test", Arrays.asList(root), () -> os).get(0)::root);
        }
    }

    @Test
    void missingDiscoveredBasesAreSkippedEvenWhenHomeIsConfigured() throws IOException {
        Path home = root.resolve("home");
        Path temp = root.resolve("temp");
        System.setProperty("user.home", home.toString());
        System.setProperty("java.io.tmpdir", temp.toString());
        assertTrue(NativeDirectories.candidates("test", () -> OperatingSystem.MACOS).isEmpty());
        Files.createFile(temp);
        System.setProperty("natives.userHome", home.toString());
        List<DirectoryCandidate> candidates = NativeDirectories.candidates("test",
                () -> OperatingSystem.MACOS);
        assertEquals(1, candidates.size());
        assertEquals(home.resolve(".jme3/test"), candidates.get(0).root());
        assertFalse(Files.exists(home));
        Files.createDirectories(home.resolve("Library/Caches"));
        candidates = NativeDirectories.candidates("test", () -> OperatingSystem.MACOS);
        assertEquals(home.resolve("Library/Caches/.jme3/test"), candidates.get(0).root());
        assertEquals(home.resolve(".jme3/test"), candidates.get(1).root());
    }

    @Test
    void discoveredBaseRemovedAfterSelectionIsNeverRecreated() throws IOException {
        Path temp = Files.createDirectory(root.resolve("temp"));
        System.setProperty("java.io.tmpdir", temp.toString());
        for (OperatingSystem os : Arrays.asList(OperatingSystem.LINUX, OperatingSystem.MACOS,
                OperatingSystem.WINDOWS)) {
            DirectoryCandidate candidate = NativeDirectories.candidates("test", () -> os).get(0);
            assertEquals(temp.resolve(".jme3/test"), candidate.root());
            Files.delete(temp);
            assertThrows(UncheckedIOException.class, candidate::get);
            assertFalse(Files.exists(temp));
            Files.createDirectory(temp);
        }
    }

    @Test
    void namespacePropertyAndExplicitPrefixApplyToEveryBase() throws IOException {
        Path home = Files.createDirectory(root.resolve("home"));
        Path cache = Files.createDirectories(home.resolve("Library/Caches"));
        Path temp = Files.createDirectory(root.resolve("temp"));
        System.setProperty("user.home", home.toString());
        System.setProperty("java.io.tmpdir", temp.toString());
        System.setProperty("natives.namespace", ".app");
        List<DirectoryCandidate> defaults = NativeDirectories.candidates("test", () -> OperatingSystem.MACOS);
        List<DirectoryCandidate> explicit = NativeDirectories.candidates(".explicit", "test",
                () -> OperatingSystem.MACOS);
        List<Path> bases = Arrays.asList(temp, cache, home);
        for (int i = 0; i < bases.size(); i++) {
            assertEquals(bases.get(i).resolve(".app/test"), defaults.get(i).root());
            assertEquals(bases.get(i).resolve(".explicit/test"), explicit.get(i).root());
            assertFalse(Files.exists(bases.get(i).resolve(".app")));
        }
        assertEquals(temp.resolve(".app/test"),
                new DirectoryCandidate(temp, "test", OperatingSystems.detect()).root());
        assertEquals(temp.resolve(".app/test"), NativeDirectories
                .fromRoots("test", Arrays.asList(temp), OperatingSystems::detect).get(0).root());
        System.setProperty("natives.namespace", "  ");
        assertEquals(home.resolve(".jme3/test"),
                NativeDirectories.candidates("test", () -> OperatingSystem.MACOS).get(2).root());
        System.setProperty("natives.namespace", "../escape");
        assertThrows(IllegalArgumentException.class,
                () -> NativeDirectories.candidates("test", OperatingSystems::detect));
        assertEquals(home.resolve(".explicit/test"),
                NativeDirectories.candidates(".explicit", "test", () -> OperatingSystem.MACOS).get(2).root());
    }

    @Test
    void blankOverridesUseDefaultsAndRelativeOverridesAreRejected() {
        System.setProperty("java.io.tmpdir", root.toString());
        for (String property : Arrays.asList("natives.tempDir", "natives.userHome", "natives.cacheDir"))
            System.setProperty(property, "  ");
        assertEquals(root.resolve(".jme3/test"),
                NativeDirectories.candidates("test", OperatingSystems::detect).get(0).root());
        for (String property : Arrays.asList("natives.tempDir", "natives.userHome", "natives.cacheDir")) {
            System.setProperty(property, "relative/path");
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> NativeDirectories.candidates("test", OperatingSystems::detect)).getMessage()
                    .contains(property));
            assertEquals(root.resolve(".jme3/test"), NativeDirectories
                    .fromRoots("test", Arrays.asList(root), OperatingSystems::detect).get(0).root());
            System.clearProperty(property);
        }
    }

    @Test
    void discoveryIsLazyOrderedAndDeduplicated() {
        Path a = root.resolve("a"), b = root.resolve("b");
        List<DirectoryCandidate> candidates = NativeDirectories.fromRoots("test", Arrays.asList(a, b, a),
                OperatingSystems::detect);
        assertEquals(2, candidates.size());
        assertEquals(a.resolve(".jme3/test"), candidates.get(0).root());
        assertEquals(b.resolve(".jme3/test"), candidates.get(1).root());
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
        assertThrows(UnsupportedOperationException.class, () -> candidates.clear());
    }

    @Test
    void rejectsPrefixesThatEscapeTheBase() {
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
            Files.createDirectories(root.resolve("home/Library/Caches"));
            System.setProperty("user.home", root.resolve("home").toString());
            System.setProperty("java.io.tmpdir", Files.createDirectory(root.resolve("temp")).toString());
            List<DirectoryCandidate> candidates = NativeDirectories.candidates("injected", () -> {
                calls.incrementAndGet();
                return com.jme3.nativebootstrap.common.OperatingSystem.MACOS;
            });
            assertEquals(root.resolve("home/Library/Caches/.jme3/injected"), candidates.get(1).root());
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
    void onlyTheApplicationDirectoryIsHidden() throws IOException {
        Path base = Files.createDirectory(root.resolve("visible-base"));
        OperatingSystem os = OperatingSystems.detect();
        for (String prefix : Arrays.asList("my-app", ".my-app")) {
            DirectoryCandidate candidate = NativeDirectories
                    .candidates(prefix, "bindings", Arrays.asList(base), () -> os).get(0);
            Path expected = base.resolve(".my-app/bindings");
            assertEquals(expected, candidate.root());
            Path fresh = candidate.get();
            assertEquals(expected.toRealPath(), fresh.getParent());
            assertTrue(fresh.getFileName().toString().startsWith("bindings-"));
            for (Path path : Arrays.asList(base, expected.getParent(), expected, fresh)) {
                boolean hidden = os == OperatingSystem.WINDOWS ? (Boolean) Files.getAttribute(path,
                        "dos:hidden") : Files.isHidden(path);
                assertEquals(path.equals(expected.getParent()), hidden, path.toString());
            }
        }
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
    void rejectsZipFileSystemBeforeCreatingDirectories() throws IOException {
        Path archive = root.resolve("filesystem.zip");
        java.net.URI uri = java.net.URI.create("jar:" + archive.toUri());
        try (java.nio.file.FileSystem zip = java.nio.file.FileSystems.newFileSystem(uri,
                java.util.Collections.singletonMap("create", "true"))) {
            Path zipRoot = zip.getPath("/");
            for (Path candidateRoot : Arrays.asList(zipRoot, zipRoot.resolve("missing/parent"))) {
                for (com.jme3.nativebootstrap.common.OperatingSystem os : Arrays.asList(
                        com.jme3.nativebootstrap.common.OperatingSystem.WINDOWS, OperatingSystems.detect())) {
                    assertThrows(UncheckedIOException.class, () -> NativeDirectories
                            .fromRoots("test", Arrays.asList(candidateRoot), () -> os).get(0).get());
                    try (Stream<Path> entries = Files.list(zipRoot)) {
                        assertEquals(0, entries.count());
                    }
                }
            }
        }
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
        assertEquals(real.resolve(".jme3/test").toRealPath(), created.getParent());
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
            Files.deleteIfExists(shared.resolve(".jme3/test"));
            Files.deleteIfExists(shared.resolve(".jme3"));
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
        Path destination = root.resolve(".jme3/test");
        if (Files.isDirectory(destination)) {
            try (Stream<Path> entries = Files.list(destination)) {
                assertEquals(0, entries.count());
            }
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
            Files.deleteIfExists(child.resolve(".jme3/test"));
            Files.deleteIfExists(child.resolve(".jme3"));
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
        runDirectoryProbe(NoStoreProbe.class);
    }

    @Test
    void cacheEnvironmentVariablesOnlySelectAnExistingBaseEvenWithoutAHome() throws Exception {
        runDirectoryProbe(CacheProbe.class);
    }

    private void runDirectoryProbe(Class<?> probe) throws Exception {
        String executable = OperatingSystems.detect() == OperatingSystem.WINDOWS ? "java.exe" : "java";
        Path log = root.resolve(probe.getSimpleName() + ".log");
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", executable).toString());
        if (probe == NoStoreProbe.class
                && Double.parseDouble(System.getProperty("java.specification.version")) >= 18)
            command.add("-Djava.security.manager=allow");
        command.addAll(
                Arrays.asList("-cp", System.getProperty("test.classpath"), probe.getName(), root.toString()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        if (probe == CacheProbe.class) {
            builder.environment().put("LOCALAPPDATA", root.resolve("windows-cache").toString());
            builder.environment().put("XDG_CACHE_HOME", root.resolve("xdg-cache").toString());
        }
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    public static class CacheProbe {
        public static void main(String[] args) throws IOException {
            System.clearProperty("user.home");
            System.clearProperty("java.io.tmpdir");
            for (OperatingSystem os : Arrays.asList(OperatingSystem.WINDOWS, OperatingSystem.LINUX)) {
                Path base = Paths.get(args[0], os == OperatingSystem.WINDOWS ? "windows-cache" : "xdg-cache");
                Path destination = base.resolve(".app/lib");
                List<DirectoryCandidate> missing = NativeDirectories.candidates(".app", "lib", () -> os);
                assertFalse(missing.stream().anyMatch(c -> c.root().equals(destination)));
                assertFalse(Files.exists(base));
                Files.createDirectory(base);
                assertEquals(destination,
                        NativeDirectories.candidates(".app", "lib", () -> os).get(0).root());
                assertFalse(Files.exists(base.resolve(".app")));
            }
        }
    }

    public static class NoStoreProbe {
        public static void main(String[] args) throws Exception {
            Path base = Paths.get(args[0]);
            String deniedTemp = base.resolve("denied-temp").toString();
            System.setProperty("java.io.tmpdir", deniedTemp);
            System.setSecurityManager(new SecurityManager() {
                @Override
                public void checkRead(String file) {
                    if (deniedTemp.equals(file)) throw new SecurityException("Temp directory unavailable");
                }

                @Override
                public void checkPermission(java.security.Permission permission) {
                    if (permission.getName().equals("getFileStoreAttributes"))
                        throw new SecurityException("Mount metadata unavailable");
                }
            });
            Path directory = NativeDirectories
                    .candidates("no-filestore", Arrays.asList(base), OperatingSystems::detect).get(0).get();
            Files.delete(directory);
        }
    }

}
