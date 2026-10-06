package com.jme3.nativebootstrap.loader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Extracts dependencies and the main library together, trying destinations in order. */
public final class NativeLoader {
    private final Consumer<Path> linker;

    /** Uses System.load from this module's classloader. */
    public NativeLoader() {
        this(path -> System.load(path.toString()));
    }

    /**
     * Uses a caller-provided linker. Applications with isolated classloaders should pass
     * {@code path -> System.load(path.toString())} from the class that owns their JNI bindings.
     *
     * @param linker
     *            native loading operation
     */
    public NativeLoader(Consumer<Path> linker) {
        this.linker = Objects.requireNonNull(linker, "linker");
    }

    /**
     * Loads all binaries in list order (dependencies first, main library last). Returns the successful
     * extraction directory. Suppliers must create fresh, empty directories suitable for native loading. Every
     * invocation is a new extraction; applications should invoke this once during initialization. Files are
     * scheduled for best-effort deletion at JVM exit; native libraries cannot be explicitly unloaded. All
     * destination failures are retained as suppressed exceptions on the final UnsatisfiedLinkError. Fallback
     * stops once any binary has loaded successfully: a later failure reports the loaded binaries and
     * preserves the entire extraction until JVM exit.
     *
     * @param candidates
     *            ordered lazy directory suppliers
     * @param binaries
     *            dependencies followed by the main library
     * @return the directory containing the loaded binaries
     */
    public Path load(List<? extends Supplier<Path>> candidates, List<NativeResource> binaries) {
        List<NativeResource> resources = new ArrayList<>(binaries);
        List<? extends Supplier<Path>> destinations = new ArrayList<>(candidates);
        for (NativeResource resource : resources) Objects.requireNonNull(resource, "resource");
        for (Supplier<Path> candidate : destinations) Objects.requireNonNull(candidate, "candidate");
        if (resources.isEmpty())
            throw new IllegalArgumentException("At least one native resource is required");
        Set<String> names = new HashSet<>();
        for (NativeResource resource : resources) {
            // Reject case-only aliases as well, for case-insensitive extraction filesystems.
            if (!names.add(resource.fileName().toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate native filename: " + resource.fileName());
        }
        UnsatisfiedLinkError failure = new UnsatisfiedLinkError(
                "Native loading failed in all " + destinations.size() + " candidate directories");
        for (Supplier<Path> candidate : destinations) {
            Path directory = null;
            List<Path> files = new ArrayList<>();
            List<Path> loaded = new ArrayList<>();
            boolean owned = false;
            try {
                directory = Objects.requireNonNull(candidate.get(), "Candidate returned null")
                        .toAbsolutePath().normalize();
                try (Stream<Path> entries = Files.list(directory)) {
                    if (entries.findAny().isPresent())
                        throw new IOException("Candidate did not return an empty directory: " + directory);
                }
                owned = true;
                directory.toFile().deleteOnExit();
                for (NativeResource resource : resources) {
                    Path file = directory.resolve(resource.fileName());
                    files.add(file);
                    file.toFile().deleteOnExit();
                    try (InputStream input = resource.open()) {
                        Files.copy(input, file);
                    }
                }
                for (Path file : files) {
                    linker.accept(file);
                    loaded.add(file);
                }
                return directory;
            } catch (IOException | UncheckedIOException | SecurityException | UnsupportedOperationException
                    | UnsatisfiedLinkError e) {
                IOException attempt = new IOException("Native load attempt failed for " + candidate, e);
                failure.addSuppressed(attempt);
                if (!loaded.isEmpty()) {
                    UnsatisfiedLinkError partial = new UnsatisfiedLinkError(
                            "Native loading failed after successfully loading " + loaded
                                    + "; fallback stopped and extraction preserved at " + directory);
                    for (Throwable prior : failure.getSuppressed()) partial.addSuppressed(prior);
                    throw partial;
                }
                for (int i = files.size() - 1; i >= 0; i--) {
                    try {
                        Files.deleteIfExists(files.get(i));
                    } catch (IOException | SecurityException cleanup) {
                        attempt.addSuppressed(cleanup);
                    }
                }
                if (owned) {
                    try {
                        Files.deleteIfExists(directory);
                    } catch (IOException | SecurityException cleanup) {
                        attempt.addSuppressed(cleanup);
                    }
                }
            }
        }
        throw failure;
    }
}
