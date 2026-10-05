package com.jme3.nativebootstrap.loader;

import com.jme3.nativebootstrap.os.OperatingSystems;
import org.junit.jupiter.api.Test;
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
    void rejectsMissingResourcesBeforeExtraction() {
        assertThrows(IllegalArgumentException.class, () -> resource("missing"));
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
}
