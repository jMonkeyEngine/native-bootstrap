/* Permission-checking approach adapted from saferalloc (BSD-3-Clause).
 * Copyright (c) 2026 Riccardo Balbo. See META-INF/LICENSE-saferalloc.txt. */
package com.jme3.nativebootstrap.directories;

import com.jme3.nativebootstrap.common.OperatingSystem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

final class PrivateDirectory {
    private static final Logger LOG = Logger.getLogger(PrivateDirectory.class.getName());
    private static final int UNSAFE_DIRECTORY_EXIT = 42;
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rwx------");
    private static final Set<String> MODIFYING_MAC_PERMISSIONS = Collections
            .unmodifiableSet(new HashSet<>(Arrays.asList("write", "append", "add_file", "add_subdirectory",
                    "delete", "delete_child", "writeattr", "writeextattr", "writesecurity", "chown")));

    private PrivateDirectory() {
    }

    static Path create(Path root, String prefix, OperatingSystem os) throws IOException {
        return create(root, prefix, os, new PermissionCommands());
    }

    static Path create(Path root, String prefix, OperatingSystem os, PermissionCommands commands)
            throws IOException {
        if (Boolean.getBoolean("natives.noAdditionalChecks")) commands = null;
        if (os == OperatingSystem.WINDOWS) return createWindows(root, prefix, commands);
        switch (os) {
            case MACOS:
            case LINUX:
            case FREEBSD:
            case OPENBSD:
            case NETBSD:
            case SOLARIS:
                break;
            default:
                throw new IOException("Desktop native extraction is unsupported for " + os);
        }
        Path existing = root;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null || Files.getFileAttributeView(existing, PosixFileAttributeView.class) == null)
            throw new IOException("POSIX permissions are unavailable for " + root);
        if (!Files.isDirectory(root))
            Files.createDirectories(root, PosixFilePermissions.asFileAttribute(PRIVATE));
        root = root.toRealPath();
        Path directory = Files.createTempDirectory(root, prefix,
                PosixFilePermissions.asFileAttribute(PRIVATE));
        try {
            boolean mac = os == OperatingSystem.MACOS;
            // Optional ACL hardening supplements the mandatory Java POSIX checks.
            if (mac && commands != null)
                commands.run("chmod", Arrays.asList("-N", directory.toString()), Collections.emptyMap());
            PosixFileAttributes attrs = Files.readAttributes(directory, PosixFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || !attrs.permissions().equals(PRIVATE))
                throw new IOException("Filesystem did not enforce private permissions: " + directory);
            checkParents(root, attrs.owner(), mac, commands);
            if (mac) checkMacAcl(directory, commands);
            Path probe = Files.createTempFile(directory, "exec-", ".probe");
            try {
                Files.setPosixFilePermissions(probe, PRIVATE);
                try {
                    if (!Files.isExecutable(probe))
                        throw new IOException("Execution is denied (possibly noexec): " + directory);
                } catch (SecurityException e) {
                    // Java also checks process-execution policy when querying executable access.
                    // If that query is forbidden, let the eventual native load verify usability.
                    LOG.log(Level.FINE, "Executable access probe unavailable", e);
                }
            } finally {
                Files.deleteIfExists(probe);
            }
            return directory;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(directory);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private static void checkParents(Path root, UserPrincipal owner, boolean mac, PermissionCommands commands)
            throws IOException {
        List<Path> parents = new ArrayList<>();
        for (Path p = root; p != null; p = p.getParent()) parents.add(p);
        Collections.reverse(parents);
        boolean privateAncestor = false;
        for (Path p : parents) {
            PosixFileAttributes attrs = Files.readAttributes(p, PosixFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            int uid = ((Number) Files.getAttribute(p, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue();
            if (!attrs.isDirectory() || (!attrs.owner().equals(owner) && uid != 0))
                throw new IOException("Untrusted directory owner: " + p);
            if (mac) checkMacAcl(p, commands);
            Set<PosixFilePermission> mode = attrs.permissions();
            // macOS ACLs may grant traversal despite absent POSIX execute bits.
            if ((mac || !privateAncestor) && (mode.contains(PosixFilePermission.GROUP_WRITE)
                    || mode.contains(PosixFilePermission.OTHERS_WRITE))) {
                int unixMode = ((Number) Files.getAttribute(p, "unix:mode", LinkOption.NOFOLLOW_LINKS))
                        .intValue();
                if ((unixMode & 01000) == 0) throw new IOException("Replaceable extraction ancestor: " + p);
            }
            if (attrs.owner().equals(owner) && !mode.contains(PosixFilePermission.GROUP_EXECUTE)
                    && !mode.contains(PosixFilePermission.OTHERS_EXECUTE))
                privateAncestor = true;
        }
    }

    private static void checkMacAcl(Path path, PermissionCommands commands) throws IOException {
        if (commands == null) return;
        // Escape embedded newlines in names so they cannot masquerade as ACL entries.
        PermissionCommands.Result result = commands.run("ls", Arrays.asList("-ldbe", path.toString()),
                Collections.emptyMap());
        if (result == null || !result.succeeded()) return;
        String listing = result.output;
        for (String line : listing.split("\n")) {
            if (!line.matches("\\s*\\d+:.*")) continue;
            int allow = line.indexOf(" allow ");
            if (allow < 0) continue;
            String permissions = line.substring(allow + 7);
            for (String permission : permissions.split(",")) {
                if (MODIFYING_MAC_PERMISSIONS.contains(permission.trim()))
                    throw new IOException("Modifying macOS ACL on extraction path: " + path);
            }
        }
    }

    private static Path createWindows(Path root, String prefix, PermissionCommands commands)
            throws IOException {
        if (!Files.isDirectory(root)) Files.createDirectories(root);
        root = root.toRealPath();
        Path directory = root.resolve(prefix + UUID.randomUUID());
        PermissionCommands.Result result = null;
        if (commands != null) {
            try (InputStream input = PrivateDirectory.class
                    .getResourceAsStream("/com/jme3/nativebootstrap/directories/private-directory.ps1")) {
                if (input != null) {
                    ByteArrayOutputStream contents = new ByteArrayOutputStream();
                    copy(input, contents);
                    String encoded = java.util.Base64.getEncoder()
                            .encodeToString(new String(contents.toByteArray(), StandardCharsets.UTF_8)
                                    .getBytes(StandardCharsets.UTF_16LE));
                    result = commands.run("powershell",
                            Arrays.asList("-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                                    encoded),
                            Collections.singletonMap("NATIVE_BOOTSTRAP_DIRECTORY", directory.toString()));
                }
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException | SecurityException e) {
                LOG.log(Level.FINE, "Windows permission helper unavailable", e);
            }
        }
        if (result != null && result.exitCode == UNSAFE_DIRECTORY_EXIT)
            throw new IOException("Unsafe Windows extraction directory: " + result.output.trim());
        if (result != null && result.succeeded() && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            try {
                verifyWritable(directory);
                return directory.toRealPath();
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(directory);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
        }
        // Never reuse a directory left behind by an unsuccessful or interrupted helper.
        LOG.fine("Using Java directory creation without PowerShell ACL validation");
        Path fallback = Files.createTempDirectory(root, prefix);
        try {
            restrictWindowsAcl(fallback);
            verifyWritable(fallback);
            return fallback.toRealPath();
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(fallback);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private static void restrictWindowsAcl(Path directory) {
        try {
            AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (view == null) return;
            // Obtain the actual owner from the new directory, never from mutable user.name.
            AclEntry owner = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
                    .setPermissions(AclEntryPermission.values())
                    .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build();
            view.setAcl(Collections.singletonList(owner));
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            // Some providers cannot change ACLs; retain the fresh directory's inherited permissions.
            LOG.log(Level.FINE, "Java ACL restriction unavailable", e);
        }
    }

    private static void verifyWritable(Path directory) throws IOException {
        Path probe = Files.createTempFile(directory, "write-", ".probe");
        Files.delete(probe);
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
    }
}
