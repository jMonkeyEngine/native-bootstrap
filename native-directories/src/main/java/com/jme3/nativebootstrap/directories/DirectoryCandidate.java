package com.jme3.nativebootstrap.directories;

import com.jme3.nativebootstrap.common.OperatingSystem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A lazy extraction destination. Each call creates a distinct directory with best-effort permission
 * hardening.
 */
public final class DirectoryCandidate implements Supplier<Path> {
    private static final Logger LOG = Logger.getLogger(DirectoryCandidate.class.getName());
    private final Path base;
    private final Path root;
    private final String prefix;
    private final OperatingSystem operatingSystem;
    private final boolean createBase;

    /**
     * Uses a manually specified base with the configured application prefix and library namespace.
     *
     * @param base
     *            base directory to create on demand
     * @param namespace
     *            application or library name
     * @param operatingSystem
     *            platform used for permission checks
     */
    public DirectoryCandidate(Path base, String namespace, OperatingSystem operatingSystem) {
        this(base, NativeDirectories.defaultHomePrefix(), namespace, operatingSystem, true);
    }

    DirectoryCandidate(Path base, String homePrefix, String namespace, OperatingSystem operatingSystem,
            boolean createBase) {
        NativeDirectories.validateHomePrefix(homePrefix);
        NativeDirectories.validateNamespace(namespace);
        this.base = base.toAbsolutePath().normalize();
        String hiddenPrefix = homePrefix.startsWith(".") ? homePrefix : "." + homePrefix;
        this.root = this.base.resolve(hiddenPrefix).resolve(namespace);
        this.prefix = namespace + "-";
        this.operatingSystem = operatingSystem;
        this.createBase = createBase;
    }

    DirectoryCandidate(OperatingSystem operatingSystem) {
        this.base = null;
        this.root = null;
        this.prefix = null;
        this.operatingSystem = operatingSystem;
        this.createBase = false;
    }

    /**
     * Returns the stable parent of the fresh extraction directories (base/.prefix/namespace).
     *
     * @return the configured absolute root
     * @throws UnsupportedOperationException
     *             if this is a virtual mobile candidate
     */
    public Path root() {
        if (root == null) throw new UnsupportedOperationException("No root directory available");
        return root;
    }

    /**
     * Returns the operating system selected when this candidate was created.
     *
     * @return the selected operating system
     */
    public OperatingSystem operatingSystem() {
        return operatingSystem;
    }

    /**
     * Creates a fresh extraction directory, or throws UncheckedIOException. Only explicit bases can be
     * created. The natives.noAdditionalChecks system property disables external permission helpers when true;
     * Java permission checks and directory creation remain active. The returned directory is scheduled for
     * best-effort deletion at JVM exit; callers must arrange cleanup for any files they add.
     */
    @Override
    public Path get() {
        try {
            if (root == null) throw new IOException("Mobile candidate has no extraction directory");
            try {
                root.toFile();
            } catch (UnsupportedOperationException e) {
                throw new IOException("Native extraction requires a file-backed path: " + root, e);
            }
            Path directory = PrivateDirectory.create(base, root, prefix, operatingSystem, createBase,
                    new PermissionCommands());
            directory.toFile().deleteOnExit();
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare native directory under " + root, e);
        }
    }

    @Override
    public String toString() {
        return root == null ? operatingSystem + " candidate" : root.toString();
    }
}
