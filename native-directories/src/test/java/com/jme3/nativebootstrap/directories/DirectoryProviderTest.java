package com.jme3.nativebootstrap.directories;

import com.jme3.nativebootstrap.common.OperatingSystem;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DirectoryProviderTest {
    @TempDir
    Path root;

    @Test
    void createsPrivateDirectoriesWhenMountDiscoveryIsUnavailable() throws IOException {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        Provider provider = new Provider(root.getFileSystem());
        Path created = PrivateDirectory.create(provider.path(root), "provider-", OperatingSystem.LINUX, null);
        assertTrue(Files.isDirectory(created));
        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(created, LinkOption.NOFOLLOW_LINKS));
        assertEquals(0, provider.mountQueries);
        assertTrue(provider.permissionReads > 0);
        assertEquals(0, provider.followedPermissionReads);
    }

    @Test
    void deniedPermissionViewReadFailsBeforeDirectoryCreation() {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        Provider provider = new Provider(root.getFileSystem());
        provider.denied = true;
        assertThrows(AccessDeniedException.class,
                () -> PrivateDirectory.create(provider.path(root), "provider-", OperatingSystem.LINUX, null));
        assertEquals(0, provider.creations);
        assertEquals(0, provider.mountQueries);
    }

    @Test
    void unsupportedPermissionViewReadFailsBeforeDirectoryCreation() {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        Provider provider = new Provider(root.getFileSystem());
        provider.unsupported = true;
        assertThrows(UnsupportedOperationException.class,
                () -> PrivateDirectory.create(provider.path(root), "provider-", OperatingSystem.LINUX, null));
        assertEquals(0, provider.creations);
    }

    @Test
    void canonicalAliasesStillWorkWithPermissionPreflight() throws IOException {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path alias = Files.createSymbolicLink(root.resolve("alias"), destination);
        Provider provider = new Provider(root.getFileSystem());
        Path created = PrivateDirectory.create(provider.path(alias), "provider-", OperatingSystem.LINUX,
                null);
        assertEquals(destination.toRealPath(), Provider.unwrap(created.getParent()));
        assertEquals(0, provider.mountQueries);
        assertEquals(0, provider.followedPermissionReads);
    }

    private static final class Provider extends FileSystemProvider {
        private final FileSystem original;
        private final FileSystem filesystem;
        int mountQueries;
        int creations;
        int permissionReads;
        int followedPermissionReads;
        boolean denied;
        boolean unsupported;

        Provider(FileSystem original) {
            this.original = original;
            this.filesystem = new FileSystem() {
                @Override
                public FileSystemProvider provider() {
                    return Provider.this;
                }

                @Override
                public void close() {
                }

                @Override
                public boolean isOpen() {
                    return original.isOpen();
                }

                @Override
                public boolean isReadOnly() {
                    return original.isReadOnly();
                }

                @Override
                public String getSeparator() {
                    return original.getSeparator();
                }

                @Override
                public Iterable<Path> getRootDirectories() {
                    List<Path> roots = new ArrayList<>();
                    for (Path root : original.getRootDirectories()) roots.add(path(root));
                    return roots;
                }

                @Override
                public Iterable<FileStore> getFileStores() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Set<String> supportedFileAttributeViews() {
                    return original.supportedFileAttributeViews();
                }

                @Override
                public Path getPath(String first, String... more) {
                    return path(original.getPath(first, more));
                }

                @Override
                public PathMatcher getPathMatcher(String pattern) {
                    return value -> original.getPathMatcher(pattern).matches(unwrap(value));
                }

                @Override
                public UserPrincipalLookupService getUserPrincipalLookupService() {
                    return original.getUserPrincipalLookupService();
                }

                @Override
                public WatchService newWatchService() throws IOException {
                    return original.newWatchService();
                }
            };
        }

        Path path(Path delegate) {
            return (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[] { Path.class },
                    new WrappedPath(this, delegate));
        }

        static Path unwrap(Path path) {
            if (Proxy.isProxyClass(path.getClass())
                    && Proxy.getInvocationHandler(path) instanceof WrappedPath)
                return ((WrappedPath) Proxy.getInvocationHandler(path)).delegate;
            return path;
        }

        @Override
        public String getScheme() {
            return "permission-probe";
        }

        @Override
        public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileSystem getFileSystem(URI uri) {
            return filesystem;
        }

        @Override
        public Path getPath(URI uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                FileAttribute<?>... attributes) throws IOException {
            return original.provider().newByteChannel(unwrap(path), options, attributes);
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path directory,
                DirectoryStream.Filter<? super Path> filter) throws IOException {
            DirectoryStream<Path> entries = original.provider().newDirectoryStream(unwrap(directory),
                    entry -> filter.accept(path(entry)));
            return new DirectoryStream<Path>() {
                @Override
                public Iterator<Path> iterator() {
                    Iterator<Path> values = entries.iterator();
                    return new Iterator<Path>() {
                        @Override
                        public boolean hasNext() {
                            return values.hasNext();
                        }

                        @Override
                        public Path next() {
                            return path(values.next());
                        }
                    };
                }

                @Override
                public void close() throws IOException {
                    entries.close();
                }
            };
        }

        @Override
        public void createDirectory(Path path, FileAttribute<?>... attributes) throws IOException {
            ++creations;
            original.provider().createDirectory(unwrap(path), attributes);
        }

        @Override
        public void delete(Path path) throws IOException {
            original.provider().delete(unwrap(path));
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) throws IOException {
            original.provider().copy(unwrap(source), unwrap(target), options);
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            original.provider().move(unwrap(source), unwrap(target), options);
        }

        @Override
        public boolean isSameFile(Path first, Path second) throws IOException {
            return original.provider().isSameFile(unwrap(first), unwrap(second));
        }

        @Override
        public boolean isHidden(Path path) throws IOException {
            return original.provider().isHidden(unwrap(path));
        }

        @Override
        public FileStore getFileStore(Path path) throws IOException {
            ++mountQueries;
            throw new IOException("Mount discovery is unavailable");
        }

        @Override
        public void checkAccess(Path path, AccessMode... modes) throws IOException {
            original.provider().checkAccess(unwrap(path), modes);
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
                LinkOption... options) {
            V view = original.provider().getFileAttributeView(unwrap(path), type, options);
            if (view == null || type != PosixFileAttributeView.class) return view;
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                    (proxy, method, args) -> {
                        if (method.getName().equals("readAttributes")) {
                            ++permissionReads;
                            if (!Arrays.asList(options).contains(LinkOption.NOFOLLOW_LINKS))
                                ++followedPermissionReads;
                            if (denied) throw new AccessDeniedException(path.toString());
                            if (unsupported)
                                throw new UnsupportedOperationException("Permission view cannot be read");
                        }
                        try {
                            return method.invoke(view, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    }));
        }

        @Override
        public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
                LinkOption... options) throws IOException {
            return original.provider().readAttributes(unwrap(path), type, options);
        }

        @Override
        public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
                throws IOException {
            return original.provider().readAttributes(unwrap(path), attributes, options);
        }

        @Override
        public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
                throws IOException {
            original.provider().setAttribute(unwrap(path), attribute, value, options);
        }
    }

    private static final class WrappedPath implements java.lang.reflect.InvocationHandler {
        private final Provider provider;
        private final Path delegate;

        WrappedPath(Provider provider, Path delegate) {
            this.provider = provider;
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments)
                throws Throwable {
            if (method.getName().equals("getFileSystem")) return provider.filesystem;
            Object[] values = arguments == null ? null : arguments.clone();
            if (values != null) for (int i = 0; i < values.length; i++)
                if (values[i] instanceof Path) values[i] = Provider.unwrap((Path) values[i]);
            try {
                Object value = method.invoke(delegate, values);
                return value instanceof Path ? provider.path((Path) value) : value;
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
