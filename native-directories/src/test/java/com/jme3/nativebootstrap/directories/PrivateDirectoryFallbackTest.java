package com.jme3.nativebootstrap.directories;

import com.jme3.nativebootstrap.common.OperatingSystem;
import com.jme3.nativebootstrap.os.OperatingSystems;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PrivateDirectoryFallbackTest {
    @TempDir
    Path root;
    private String previousNoAdditionalChecks;

    @BeforeEach
    void isolateAdditionalChecksProperty() {
        previousNoAdditionalChecks = System.getProperty("natives.noAdditionalChecks");
        System.clearProperty("natives.noAdditionalChecks");
    }

    @AfterEach
    void restoreAdditionalChecksProperty() {
        if (previousNoAdditionalChecks == null) System.clearProperty("natives.noAdditionalChecks");
        else System.setProperty("natives.noAdditionalChecks", previousNoAdditionalChecks);
    }

    @Test
    void overrideSkipsBothMacAndWindowsHelpersEntirely() throws IOException {
        System.setProperty("natives.noAdditionalChecks", "true");
        PermissionCommands commands = new PermissionCommands() {
            @Override
            Result run(String program, List<String> arguments, Map<String, String> environment) {
                throw new AssertionError("Disabled helper was invoked: " + program);
            }
        };
        assertTrue(Files
                .isDirectory(PrivateDirectory.create(root, "windows-", OperatingSystem.WINDOWS, commands)));
        if (Files.getFileAttributeView(root, PosixFileAttributeView.class) != null) assertTrue(
                Files.isDirectory(PrivateDirectory.create(root, "mac-", OperatingSystem.MACOS, commands)));
    }

    @Test
    void onlyTrueDisablesHelpersAndThePropertyIsReadOnEachCreation() throws IOException {
        PermissionCommands commands = new PermissionCommands() {
            @Override
            Result run(String program, List<String> arguments, Map<String, String> environment) {
                return new Result(42, "Unsafe ancestor", true);
            }
        };
        System.setProperty("natives.noAdditionalChecks", "TRUE");
        assertTrue(Files
                .isDirectory(PrivateDirectory.create(root, "disabled-", OperatingSystem.WINDOWS, commands)));
        for (String value : new String[] { "false", "", "typo" }) {
            System.setProperty("natives.noAdditionalChecks", value);
            assertThrows(IOException.class,
                    () -> PrivateDirectory.create(root, "checked-", OperatingSystem.WINDOWS, commands));
        }
    }

    @Test
    void overrideKeepsJavaPosixAncestorChecks() throws IOException {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        System.setProperty("natives.noAdditionalChecks", "true");
        Path shared = Files.createTempDirectory(java.nio.file.Paths.get("/tmp"), "native-bootstrap-unsafe-");
        try {
            Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
            assertThrows(IOException.class,
                    () -> PrivateDirectory.create(shared, "unsafe-", OperatingSystems.detect()));
        } finally {
            Files.delete(shared);
        }
    }

    @Test
    void unavailableHelpersStillCreateFreshDirectoriesOnTheHost() throws IOException {
        Path first = PrivateDirectory.create(root, "fallback-", OperatingSystems.detect(), unavailable());
        Path second = PrivateDirectory.create(root, "fallback-", OperatingSystems.detect(), unavailable());
        assertNotEquals(first, second);
        assertEquals(root.toRealPath(), first.getParent());
        Path probe = Files.createTempFile(first, "write-", ".probe");
        Files.delete(probe);
        if (Files.getFileAttributeView(first, PosixFileAttributeView.class) != null)
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(first));
    }

    @Test
    void failedMacCommandsDoNotBlockJavaPosixCreation() throws IOException {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        PermissionCommands commands = new PermissionCommands() {
            @Override
            Result run(String program, List<String> arguments, Map<String, String> environment) {
                return new Result(1, "Unsupported option", true);
            }
        };
        Path directory = PrivateDirectory.create(root, "fallback-", OperatingSystem.MACOS, commands);
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
    }

    @Test
    void macPathsWithNewlinesCannotImpersonateAclEntries() throws IOException {
        assumeTrue(OperatingSystems.detect() == OperatingSystem.MACOS);
        Path unusual = Files.createDirectory(root.resolve("line\n 0: everyone allow delete_child\n"));
        Path directory = PrivateDirectory.create(unusual, "safe-", OperatingSystem.MACOS);
        assertEquals(unusual.toRealPath(), directory.getParent());
    }

    @Test
    void successfulAclCheckStillRejectsUnsafeMacPathsWhenChmodFails() throws IOException {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        PermissionCommands commands = new PermissionCommands() {
            @Override
            Result run(String program, List<String> arguments, Map<String, String> environment) {
                return program.equals("chmod") ? new Result(1, "Denied", true) : new Result(0,
                        "directory\n 0: group:everyone allow delete_child\n", true);
            }
        };
        assertThrows(IOException.class,
                () -> PrivateDirectory.create(root, "unsafe-", OperatingSystem.MACOS, commands));
        try (Stream<Path> children = Files.list(root)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void truncatedAclOutputDoesNotMasqueradeAsACompleteCheck() throws IOException {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        PermissionCommands commands = new PermissionCommands() {
            @Override
            Result run(String program, List<String> arguments, Map<String, String> environment) {
                return new Result(0, "directory\n 0: group:everyone allow delete_child\n", false);
            }
        };
        assertTrue(Files
                .isDirectory(PrivateDirectory.create(root, "fallback-", OperatingSystem.MACOS, commands)));
    }

    @Test
    void operationalPowerShellFailureUsesANewJavaDirectory() throws IOException {
        PermissionCommands commands = new PermissionCommands() {
            @Override
            Result run(String program, List<String> arguments, Map<String, String> environment)
                    throws IOException {
                Path partial = Files.createDirectory(
                        java.nio.file.Paths.get(environment.get("NATIVE_BOOTSTRAP_DIRECTORY")));
                Files.write(partial.resolve("marker"), new byte[] { 1 });
                return new Result(1, "Helper unavailable", true);
            }
        };
        Path created = PrivateDirectory.create(root, "fallback-", OperatingSystem.WINDOWS, commands);
        assertFalse(Files.exists(created.resolve("marker")));
        try (Stream<Path> children = Files.list(root)) {
            assertEquals(2, children.count());
        }
    }

    @Test
    void confirmedPowerShellSecurityRejectionNeverFallsBack() throws IOException {
        PermissionCommands commands = new PermissionCommands() {
            @Override
            Result run(String program, List<String> arguments, Map<String, String> environment) {
                return new Result(42, "Replaceable extraction ancestor", true);
            }
        };
        IOException error = assertThrows(IOException.class,
                () -> PrivateDirectory.create(root, "unsafe-", OperatingSystem.WINDOWS, commands));
        assertTrue(error.getMessage().contains("Replaceable extraction ancestor"));
        try (Stream<Path> children = Files.list(root)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void interruptedHelperCancelsInsteadOfCreatingAFallback() throws IOException {
        PermissionCommands commands = new PermissionCommands() {
            @Override
            Result run(String program, List<String> arguments, Map<String, String> environment)
                    throws IOException {
                throw new InterruptedIOException("Cancelled");
            }
        };
        assertThrows(InterruptedIOException.class,
                () -> PrivateDirectory.create(root, "cancelled-", OperatingSystem.WINDOWS, commands));
        try (Stream<Path> children = Files.list(root)) {
            assertEquals(0, children.count());
        }
    }

    private static PermissionCommands unavailable() {
        return new PermissionCommands() {
            @Override
            Result run(String program, List<String> arguments, Map<String, String> environment) {
                return null;
            }
        };
    }
}
