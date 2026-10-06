package com.jme3.nativebootstrap.loader;

import com.jme3.nativebootstrap.os.OperatingSystems;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import com.jme3.nativebootstrap.directories.NativeDirectories;
import com.jme3.nativebootstrap.directories.DirectoryCandidate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class NativeLoaderTest {
    @TempDir
    Path root;
    private String previousWithOsLibraries;
    private String previousPreferOsLibraries;

    @BeforeEach
    void isolateOsLibraryProperties() {
        previousWithOsLibraries = System.getProperty("natives.withOsLibraries");
        previousPreferOsLibraries = System.getProperty("natives.preferOsLibraries");
        System.setProperty("natives.withOsLibraries", "false");
        System.clearProperty("natives.preferOsLibraries");
    }

    @AfterEach
    void restoreOsLibraryProperties() {
        restoreProperty("natives.withOsLibraries", previousWithOsLibraries);
        restoreProperty("natives.preferOsLibraries", previousPreferOsLibraries);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    NativeResource resource(String name) {
        return NativeResource.fromClasspath(getClass(), "/native/" + name + ".bin");
    }

    @Test
    void extractsAllDependenciesBeforeLoadingInOrder() {
        List<String> loaded = new ArrayList<>();
        Path directory = new NativeLoader(p -> {
            assertTrue(Files.exists(p.getParent().resolve("first.bin")));
            assertTrue(Files.exists(p.getParent().resolve("second.bin")));
            loaded.add(p.getFileName().toString());
        }).load(NativeDirectories.fromRoots("test", Arrays.asList(root), OperatingSystems::detect),
                Arrays.asList(resource("first"), resource("second")));
        assertEquals(Arrays.asList("first.bin", "second.bin"), loaded);
        assertTrue(Files.isDirectory(directory));
    }

    @Test
    void fallsBackAfterDirectoryFailureAndStopsAfterSuccess() {
        DirectoryCandidate candidate = NativeDirectories
                .fromRoots("test", Arrays.asList(root), OperatingSystems::detect).get(0);
        Supplier<Path> failed = () -> {
            throw new UncheckedIOException(new IOException("unusable root"));
        };
        Supplier<Path> unused = () -> {
            throw new AssertionError("Later candidate should remain lazy");
        };
        assertNotNull(new NativeLoader(p -> {
        }).load(Arrays.asList(failed, candidate, unused), Arrays.asList(resource("first"))));
    }

    @Test
    void fallsBackAfterLoadFailureAndRemovesFailedExtraction() {
        List<Path> attempts = new ArrayList<>();
        DirectoryCandidate candidate = NativeDirectories
                .fromRoots("test", Arrays.asList(root), OperatingSystems::detect).get(0);
        Path success = new NativeLoader(p -> {
            attempts.add(p.getParent());
            if (attempts.size() == 1) throw new UnsatisfiedLinkError("mapping denied");
        }).load(Arrays.asList(candidate, candidate), Arrays.asList(resource("first")));
        assertEquals(2, attempts.size());
        assertFalse(Files.exists(attempts.get(0)));
        assertEquals(attempts.get(1), success);
    }

    @Test
    void stopsAfterPartialLoadAndPreservesTheEntireExtraction() throws IOException {
        List<Path> linked = new ArrayList<>();
        Supplier<Path> failed = () -> {
            throw new UncheckedIOException(new IOException("unusable root"));
        };
        DirectoryCandidate candidate = NativeDirectories
                .fromRoots("test", Arrays.asList(root), OperatingSystems::detect).get(0);
        Supplier<Path> unused = () -> {
            throw new AssertionError("Must not retry after a successful native load");
        };
        UnsatisfiedLinkError cause = new UnsatisfiedLinkError("missing transitive dependency");
        UnsatisfiedLinkError error = assertThrows(UnsatisfiedLinkError.class, () -> new NativeLoader(p -> {
            linked.add(p);
            if (p.getFileName().toString().equals("second.bin")) throw cause;
        }).load(Arrays.asList(failed, candidate, unused),
                Arrays.asList(resource("first"), resource("second"))));
        assertEquals(2, linked.size());
        Path directory = linked.get(0).getParent();
        assertEquals(directory.resolve("first.bin"), linked.get(0));
        assertEquals(directory.resolve("second.bin"), linked.get(1));
        assertTrue(Files.isDirectory(directory));
        for (Path file : linked) {
            assertTrue(Files.size(file) > 0);
        }
        assertTrue(error.getMessage().contains("successfully loading [" + linked.get(0) + "]"));
        assertTrue(error.getMessage().contains("extraction preserved at " + directory));
        assertEquals(2, error.getSuppressed().length);
        assertEquals("unusable root", error.getSuppressed()[0].getCause().getCause().getMessage());
        assertSame(cause, error.getSuppressed()[1].getCause());
    }

    @Test
    void retainsEveryFailureAndCleansUp() throws IOException {
        DirectoryCandidate candidate = NativeDirectories
                .fromRoots("test", Arrays.asList(root), OperatingSystems::detect).get(0);
        UnsatisfiedLinkError error = assertThrows(UnsatisfiedLinkError.class, () -> new NativeLoader(p -> {
            throw new UnsatisfiedLinkError("bad binary");
        }).load(Arrays.asList(candidate, candidate), Arrays.asList(resource("first"))));
        assertEquals(2, error.getSuppressed().length);
        try (Stream<Path> entries = Files.list(root)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void rejectsDuplicateNamesBeforeUsingAnyDirectory() {
        AtomicInteger count = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> new NativeLoader(p -> {
        }).load(Arrays.asList(() -> {
            count.incrementAndGet();
            return root;
        }), Arrays.asList(resource("first"), resource("first"))));
        assertEquals(0, count.get());
    }

    @Test
    void missingResourceFailsWhenOsLibrariesAreDisabled() {
        NativeResource missing = resource("missing");
        assertFalse(missing.available());
        UnsatisfiedLinkError error = assertThrows(UnsatisfiedLinkError.class,
                () -> new NativeLoader(p -> fail("Must not load a missing file"),
                        name -> fail("OS loading is disabled"))
                        .load(Arrays.asList(() -> root), Arrays.asList(missing)));
        assertTrue(error.getSuppressed()[0].getCause().getMessage().contains("missing.bin"));
    }

    @Test
    void rejectsNullEntriesBeforeUsingAnyDirectory() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<Path> candidate = () -> {
            calls.incrementAndGet();
            return root;
        };
        NativeLoader loader = new NativeLoader(p -> fail("Must not load invalid inputs"));
        assertThrows(NullPointerException.class, () -> loader.load(Collections.singletonList(candidate),
                Arrays.asList(resource("first"), null)));
        assertThrows(NullPointerException.class, () -> loader.load(Arrays.asList(candidate, null),
                Collections.singletonList(resource("first"))));
        assertEquals(0, calls.get());
    }

    @Test
    void doesNotDeleteFilesFromAnInvalidSupplier() throws IOException {
        Path existing = Files.write(root.resolve("existing"), "preserve".getBytes(StandardCharsets.UTF_8));
        assertThrows(UnsatisfiedLinkError.class, () -> new NativeLoader(p -> {
        }).load(Arrays.asList(() -> root), Arrays.asList(resource("first"))));
        assertEquals("preserve", new String(Files.readAllBytes(existing), StandardCharsets.UTF_8));
    }

    @Test
    void prefersClasspathByDefault() {
        System.clearProperty("natives.withOsLibraries");
        assertNotNull(new NativeLoader(p -> {
        }, name -> fail("Classpath should be tried first")).load(Arrays.asList(() -> root),
                Arrays.asList(resource("first"))));
    }

    @Test
    void fallsBackToOsForMissingResourcesByDefaultWithoutCreatingDirectories() {
        System.clearProperty("natives.withOsLibraries");
        List<String> loaded = new ArrayList<>();
        NativeResource missing = NativeResource.fromClasspath(getClass(),
                "/missing/" + System.mapLibraryName("missing"));
        assertEquals("missing", missing.libraryName());
        assertNull(new NativeLoader(p -> fail("Must not load a missing file"), loaded::add)
                .load(Arrays.asList(() -> {
                    throw new AssertionError("No extraction needed");
                }), Arrays.asList(missing)));
        assertEquals(Arrays.asList("missing"), loaded);
    }

    @Test
    void prefersOsLibrariesWithoutExtractingAnything() {
        System.setProperty("natives.withOsLibraries", "true");
        System.setProperty("natives.preferOsLibraries", "true");
        List<String> loaded = new ArrayList<>();
        assertNull(new NativeLoader(p -> fail("OS libraries should be tried first"), loaded::add)
                .load(Arrays.asList(() -> {
                    throw new AssertionError("No extraction needed");
                }), Arrays.asList(resource("first"), resource("second"))));
        assertEquals(Arrays.asList("first.bin", "second.bin"), loaded);
    }

    @Test
    void disabledOsLibrariesOverrideThePreference() {
        System.setProperty("natives.preferOsLibraries", "true");
        assertNotNull(new NativeLoader(p -> {
        }, name -> fail("OS loading is disabled")).load(Arrays.asList(() -> root),
                Arrays.asList(resource("first"))));
    }

    @Test
    void extractsWhenPreferredOsLibraryIsUnavailable() {
        System.setProperty("natives.withOsLibraries", "true");
        System.setProperty("natives.preferOsLibraries", "true");
        List<String> order = new ArrayList<>();
        assertNotNull(new NativeLoader(p -> order.add("file"), name -> {
            order.add("OS");
            throw new UnsatisfiedLinkError("not installed");
        }).load(Arrays.asList(() -> root), Arrays.asList(resource("first"))));
        assertEquals(Arrays.asList("OS", "file"), order);
    }

    @Test
    void fallsBackToOsAfterExtractedLibraryFailsToLoad() {
        System.setProperty("natives.withOsLibraries", "true");
        List<String> order = new ArrayList<>();
        Path directory = new NativeLoader(p -> {
            order.add("file");
            throw new UnsatisfiedLinkError("bad binary");
        }, name -> order.add("OS")).load(Arrays.asList(() -> root), Arrays.asList(resource("first")));
        assertEquals(Arrays.asList("file", "OS"), order);
        assertEquals(root, directory);
    }

    @Test
    void fallsBackToOsWhenNoExtractionDirectoryWorks() {
        System.setProperty("natives.withOsLibraries", "true");
        List<String> order = new ArrayList<>();
        assertNull(new NativeLoader(p -> fail("Cannot extract"), name -> order.add("OS"))
                .load(Arrays.asList(() -> {
                    order.add("directory");
                    throw new UncheckedIOException(new IOException("unwritable root"));
                }), Arrays.asList(resource("first"))));
        assertEquals(Arrays.asList("directory", "OS"), order);
    }

    @Test
    void mixesExtractedAndMissingOsLibrariesInDependencyOrder() {
        System.setProperty("natives.withOsLibraries", "true");
        List<String> order = new ArrayList<>();
        assertNotNull(
                new NativeLoader(p -> order.add("file " + p.getFileName()), name -> order.add("OS " + name))
                        .load(Arrays.asList(() -> root),
                                Arrays.asList(resource("first"), resource("missing"), resource("second"))));
        assertEquals(Arrays.asList("file first.bin", "OS missing.bin", "file second.bin"), order);
    }

    @Test
    void neverReloadsOsLibrariesAfterPartialSuccess() {
        System.setProperty("natives.withOsLibraries", "true");
        System.setProperty("natives.preferOsLibraries", "true");
        List<String> loaded = new ArrayList<>();
        UnsatisfiedLinkError osFailure = new UnsatisfiedLinkError("OS second library failed");
        UnsatisfiedLinkError fileFailure = new UnsatisfiedLinkError("file second library failed");
        UnsatisfiedLinkError error = assertThrows(UnsatisfiedLinkError.class, () -> new NativeLoader(p -> {
            throw fileFailure;
        }, name -> {
            loaded.add(name);
            if (name.equals("second.bin")) throw osFailure;
        }).load(Arrays.asList(() -> root, () -> {
            throw new AssertionError("Must not retry a partial load");
        }), Arrays.asList(resource("first"), resource("second"))));
        assertEquals(Arrays.asList("first.bin", "second.bin"), loaded);
        assertTrue(error.getMessage().contains("OS library first.bin"));
        assertEquals(2, error.getSuppressed().length);
        assertSame(osFailure, error.getSuppressed()[0]);
        assertSame(fileFailure, error.getSuppressed()[1].getCause());
        assertTrue(Files.exists(root.resolve("second.bin")));
    }

    @Test
    void retainsBothFileAndOsFailures() {
        System.setProperty("natives.withOsLibraries", "true");
        UnsatisfiedLinkError osFailure = new UnsatisfiedLinkError("not installed");
        UnsatisfiedLinkError fileFailure = new UnsatisfiedLinkError("invalid binary");
        UnsatisfiedLinkError error = assertThrows(UnsatisfiedLinkError.class, () -> new NativeLoader(p -> {
            throw fileFailure;
        }, name -> {
            throw osFailure;
        }).load(Arrays.asList(() -> root), Arrays.asList(resource("first"))));
        assertSame(fileFailure, error.getSuppressed()[0].getCause());
        assertSame(osFailure, fileFailure.getSuppressed()[0]);
    }
}
