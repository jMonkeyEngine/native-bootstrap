package com.jme3.nativebootstrap.directories;

import com.jme3.nativebootstrap.common.OperatingSystem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * A lazy extraction destination. Each call creates a distinct directory with best-effort permission
 * hardening.
 */
public final class DirectoryCandidate implements Supplier<Path> {
    private final Path root;
    private final String prefix;
    private final OperatingSystem operatingSystem;

    public DirectoryCandidate(Path root, String namespace, OperatingSystem operatingSystem) {
        this.root = root.toAbsolutePath().normalize();
        this.prefix = namespace + "-";
        this.operatingSystem = operatingSystem;
    }

    /**
     * Returns the destination root without creating or accessing it.
     *
     * @return the configured absolute root
     */
    public Path root() {
        return root;
    }

    /**
     * Creates missing parents and a fresh extraction directory, or throws UncheckedIOException. The
     * natives.noAdditionalChecks system property disables external permission helpers when true; Java
     * permission checks and directory creation remain active.
     */
    @Override
    public Path get() {
        try {
            return PrivateDirectory.create(root, prefix, operatingSystem);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare native directory under " + root, e);
        }
    }

    @Override
    public String toString() {
        return root.toString();
    }
}
