package com.jme3.nativebootstrap.directories;

import com.jme3.nativebootstrap.common.OperatingSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Discovers ordered destinations without creating directories or loading native code. */
public final class NativeDirectories {
    private NativeDirectories() {
    }

    /**
     * Tries preferred bases, JVM temp, user cache and user home in order. Linux also tries XDG_RUNTIME_DIR
     * and /dev/shm. Each base uses the same prefix and namespace layout. The prefix defaults to .jme3 and can
     * be set with natives.namespace. Android and iOS return a virtual candidate.
     *
     * @param namespace
     *            application or library name
     * @param preferredRoots
     *            roots to try before defaults
     * @param operatingSystem
     *            supplier evaluated once per list; the result is shared by its candidates
     * @return an immutable ordered list of lazy suppliers
     */
    public static List<DirectoryCandidate> candidates(String namespace, List<Path> preferredRoots,
            Supplier<OperatingSystem> operatingSystem) {
        return candidates(null, namespace, preferredRoots, operatingSystem, true);
    }

    /**
     * Returns the ordered candidates with an explicit prefix. Discovered bases must already exist. Preferred
     * bases and explicit natives.tempDir, natives.cacheDir and natives.userHome overrides can be created on
     * first use.
     *
     * @param homePrefix
     *            directory name under every base, for example ".jme3"
     * @param namespace
     *            application or library name
     * @param preferredRoots
     *            roots to try before defaults
     * @param operatingSystem
     *            supplier evaluated once per list; the result is shared by its candidates
     * @return an immutable ordered list of lazy suppliers
     */
    public static List<DirectoryCandidate> candidates(String homePrefix, String namespace,
            List<Path> preferredRoots, Supplier<OperatingSystem> operatingSystem) {
        validateHomePrefix(homePrefix);
        return candidates(homePrefix, namespace, preferredRoots, operatingSystem, true);
    }

    /**
     * Returns the default ordered candidates.
     *
     * @param namespace
     *            application or library name
     * @param operatingSystem
     *            supplier evaluated once per list
     * @return an immutable ordered list of lazy suppliers
     */
    public static List<DirectoryCandidate> candidates(String namespace,
            Supplier<OperatingSystem> operatingSystem) {
        return candidates(namespace, Collections.emptyList(), operatingSystem);
    }

    /**
     * Returns the default ordered candidates with a custom prefix for every base.
     *
     * @param homePrefix
     *            directory name under every base, for example ".jme3"
     * @param namespace
     *            application or library name
     * @param operatingSystem
     *            supplier evaluated once per list
     * @return an immutable ordered list of lazy suppliers
     */
    public static List<DirectoryCandidate> candidates(String homePrefix, String namespace,
            Supplier<OperatingSystem> operatingSystem) {
        return candidates(homePrefix, namespace, Collections.emptyList(), operatingSystem);
    }

    /**
     * Uses only these roots, preserving order and removing duplicates. Android and iOS ignore the roots and
     * return only a virtual candidate. No filesystem access occurs.
     *
     * @param namespace
     *            application or library name
     * @param roots
     *            explicit base directories
     * @param operatingSystem
     *            supplier evaluated once per list
     * @return an immutable ordered list of lazy suppliers
     */
    public static List<DirectoryCandidate> fromRoots(String namespace, List<Path> roots,
            Supplier<OperatingSystem> operatingSystem) {
        return candidates(null, namespace, roots, operatingSystem, false);
    }

    private static List<DirectoryCandidate> candidates(String homePrefix, String namespace,
            List<Path> preferredRoots, Supplier<OperatingSystem> operatingSystem, boolean includeDefaults) {
        validateNamespace(namespace);
        Objects.requireNonNull(operatingSystem, "operatingSystem");
        OperatingSystem os = Objects.requireNonNull(operatingSystem.get(), "OS supplier returned null");
        if (os == OperatingSystem.ANDROID || os == OperatingSystem.IOS)
            return Collections.singletonList(new DirectoryCandidate(os));
        if (homePrefix == null) {
            homePrefix = defaultHomePrefix();
            validateHomePrefix(homePrefix);
        }

        Roots roots = new Roots();
        for (Path base : preferredRoots) {
            roots.createAndUse(base);
        }
        if (includeDefaults) {
            Path temp = overridePath("natives.tempDir");
            if (temp != null) {
                roots.createAndUse(temp);
            } else {
                roots.use(absolutePath(System.getProperty("java.io.tmpdir")));
            }

            Path homeOverride = overridePath("natives.userHome");
            Path userHome = homeOverride;
            if (userHome == null) {
                userHome = absolutePath(System.getProperty("user.home"));
            }
            Path cache = overridePath("natives.cacheDir");
            if (cache != null) {
                roots.createAndUse(cache);
            } else {
                String cacheUnderHome;
                switch (os) {
                    case WINDOWS:
                        cache = absolutePath(System.getenv("LOCALAPPDATA"));
                        cacheUnderHome = "AppData/Local";
                        break;
                    case MACOS:
                        cacheUnderHome = "Library/Caches";
                        break;
                    default:
                        cache = absolutePath(System.getenv("XDG_CACHE_HOME"));
                        cacheUnderHome = ".cache";
                        break;
                }
                if (cache == null && userHome != null) {
                    cache = userHome.resolve(cacheUnderHome);
                }
                roots.use(cache);
            }

            if (homeOverride != null) {
                roots.createAndUse(homeOverride);
            } else {
                roots.use(userHome);
            }
            if (os == OperatingSystem.LINUX) {
                roots.use(absolutePath(System.getenv("XDG_RUNTIME_DIR")));
                roots.use(Paths.get("/dev/shm"));
            }
        }

        List<DirectoryCandidate> candidates = new ArrayList<>();
        for (Map.Entry<Path, Boolean> entry : roots.bases.entrySet()) {
            Path base = entry.getKey();
            boolean createBase = entry.getValue();
            candidates.add(new DirectoryCandidate(base, homePrefix, namespace, os, createBase));
        }
        return Collections.unmodifiableList(candidates);
    }

    private static final class Roots {
        private final Map<Path, Boolean> bases = new LinkedHashMap<>();

        // The candidate can create this base on first use.
        void createAndUse(Path base) {
            bases.put(base.toAbsolutePath().normalize(), true);
        }

        void use(Path base) {
            if (base == null || !base.isAbsolute()) return;
            Path normalized = base.normalize();
            try {
                if (Files.isDirectory(normalized)) {
                    bases.putIfAbsent(normalized, false);
                }
            } catch (SecurityException denied) {
                // An inaccessible system base must not prevent trying the other candidates.
            }
        }
    }

    static String defaultHomePrefix() {
        String value = System.getProperty("natives.namespace");
        return value == null || value.trim().isEmpty() ? ".jme3" : value;
    }

    static void validateNamespace(String namespace) {
        if (namespace == null || !namespace.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("Namespace must be a simple name of 1 to 64 characters");
    }

    static void validateHomePrefix(String homePrefix) {
        if (homePrefix == null || !homePrefix.matches("[A-Za-z0-9._-]{1,64}") || homePrefix.equals(".")
                || homePrefix.equals(".."))
            throw new IllegalArgumentException(
                    "Home prefix must be a simple directory name of 1 to 64 characters");
    }

    private static Path absolutePath(String value) {
        if (value == null || value.isEmpty()) return null;
        Path path = Paths.get(value);
        return path.isAbsolute() ? path : null;
    }

    private static Path overridePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) return null;
        Path path = Paths.get(value);
        if (!path.isAbsolute()) throw new IllegalArgumentException(property + " must be an absolute path");
        return path;
    }

}
