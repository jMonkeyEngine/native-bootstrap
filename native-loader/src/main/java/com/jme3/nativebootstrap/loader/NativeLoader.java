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
    private final Consumer<String> osLinker;

    /** Uses System.load and System.loadLibrary from this module's classloader. */
    public NativeLoader() {
        this(path -> System.load(path.toString()));
    }

    /**
     * Uses a caller-provided file linker and System.loadLibrary from this module's classloader. Applications
     * with isolated classloaders should provide both linkers from the class that owns their JNI bindings.
     *
     * @param linker
     *            native loading operation
     */
    public NativeLoader(Consumer<Path> linker) {
        this(linker, name -> System.loadLibrary(name));
    }

    /**
     * Uses caller-provided file and OS linkers, for example {@code path -> System.load(path.toString())} and
     * {@code name -> System.loadLibrary(name)} from the class that owns the JNI bindings.
     *
     * @param linker
     *            native loading operation for extracted files
     * @param osLinker
     *            native loading operation for logical OS library names
     */
    public NativeLoader(Consumer<Path> linker, Consumer<String> osLinker) {
        this.linker = Objects.requireNonNull(linker, "linker");
        this.osLinker = Objects.requireNonNull(osLinker, "osLinker");
    }

    /**
     * Loads all binaries in list order (dependencies first, main library last). Returns the successful
     * extraction directory, or null if no extraction was needed. Suppliers must create fresh, empty
     * directories suitable for native loading. Every invocation is a new loading attempt; applications should
     * invoke this once during initialization. Files are scheduled for best-effort deletion at JVM exit;
     * native libraries cannot be explicitly unloaded. All destination failures are retained as suppressed
     * exceptions on the final UnsatisfiedLinkError. Directory fallback stops once any binary has loaded
     * successfully: a later failure reports the loaded binaries and preserves the entire extraction until JVM
     * exit. The natives.withOsLibraries property enables OS fallback (default true);
     * natives.preferOsLibraries tries OS libraries first (default false). Disabling withOsLibraries disables
     * both OS modes.
     *
     * @param candidates
     *            ordered lazy directory suppliers
     * @param binaries
     *            dependencies followed by the main library
     * @return the extraction directory, or null if no extraction was needed
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
        boolean withOsLibraries = Boolean.parseBoolean(System.getProperty("natives.withOsLibraries", "true"));
        boolean preferOsLibraries = withOsLibraries && Boolean.getBoolean("natives.preferOsLibraries");
        UnsatisfiedLinkError failure = new UnsatisfiedLinkError("Native loading failed");
        List<String> loaded = new ArrayList<>();
        int first = 0;
        // Try an OS-only prefix before creating directories, preserving dependency order.
        while (withOsLibraries && first < resources.size()
                && (preferOsLibraries || !resources.get(first).available())) {
            NativeResource resource = resources.get(first);
            try {
                osLinker.accept(resource.libraryName());
                loaded.add("OS library " + resource.libraryName());
                first++;
            } catch (UnsatisfiedLinkError | SecurityException e) {
                failure.addSuppressed(e);
                if (!resource.available()) throw loadingFailure(failure, loaded, null);
                break;
            }
        }
        if (first == resources.size()) return null;
        for (Supplier<Path> candidate : destinations) {
            Path directory = null;
            List<Path> files = new ArrayList<>();
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
                for (NativeResource resource : resources.subList(first, resources.size())) {
                    if (!resource.available()) continue;
                    Path file = directory.resolve(resource.fileName());
                    files.add(file);
                    file.toFile().deleteOnExit();
                    try (InputStream input = resource.open()) {
                        Files.copy(input, file);
                    }
                }
                for (int i = first; i < resources.size(); i++) {
                    NativeResource resource = resources.get(i);
                    // The first preferred OS attempt already failed before extraction.
                    boolean allowOs = withOsLibraries && (!preferOsLibraries || i != first);
                    loaded.add(loadBinary(resource, directory.resolve(resource.fileName()), preferOsLibraries,
                            allowOs));
                }
                return directory;
            } catch (IOException | UncheckedIOException | SecurityException | UnsupportedOperationException
                    | UnsatisfiedLinkError e) {
                IOException attempt = new IOException("Native load attempt failed for " + candidate, e);
                failure.addSuppressed(attempt);
                if (!loaded.isEmpty()) throw loadingFailure(failure, loaded, directory);
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
        if (withOsLibraries) {
            for (NativeResource resource : resources.subList(first, resources.size())) {
                try {
                    osLinker.accept(resource.libraryName());
                    loaded.add("OS library " + resource.libraryName());
                } catch (UnsatisfiedLinkError | SecurityException e) {
                    failure.addSuppressed(e);
                    throw loadingFailure(failure, loaded, null);
                }
            }
            return null;
        }
        throw failure;
    }

    private String loadBinary(NativeResource resource, Path file, boolean preferOsLibraries,
            boolean withOsLibraries) throws IOException {
        Throwable osFailure = null;
        if (withOsLibraries && (preferOsLibraries || !resource.available())) {
            try {
                osLinker.accept(resource.libraryName());
                return "OS library " + resource.libraryName();
            } catch (UnsatisfiedLinkError | SecurityException e) {
                if (!resource.available()) throw e;
                osFailure = e;
            }
        }
        if (!resource.available())
            throw new java.io.FileNotFoundException("Native resource not found: " + resource.fileName());
        try {
            linker.accept(file);
            return file.toString();
        } catch (UnsatisfiedLinkError | SecurityException | UnsupportedOperationException e) {
            if (osFailure != null) e.addSuppressed(osFailure);
            if (withOsLibraries && !preferOsLibraries) {
                try {
                    osLinker.accept(resource.libraryName());
                    return "OS library " + resource.libraryName();
                } catch (UnsatisfiedLinkError | SecurityException os) {
                    e.addSuppressed(os);
                }
            }
            throw e;
        }
    }

    private static UnsatisfiedLinkError loadingFailure(UnsatisfiedLinkError failure, List<String> loaded,
            Path directory) {
        if (loaded.isEmpty()) return failure;
        UnsatisfiedLinkError partial = new UnsatisfiedLinkError(
                "Native loading failed after successfully loading " + loaded + "; fallback stopped"
                        + (directory == null ? "; already loaded OS libraries cannot be unloaded"
                                             : " and extraction preserved at " + directory));
        for (Throwable prior : failure.getSuppressed()) partial.addSuppressed(prior);
        return partial;
    }
}
