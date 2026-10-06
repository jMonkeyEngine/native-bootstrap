package com.jme3.nativebootstrap.loader;

import java.io.IOException;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.net.URL;
import java.util.Objects;

/** A native binary with an optional classpath resource and an OS library name. */
public final class NativeResource {
    private final String fileName;
    private final URL source;

    private NativeResource(String fileName, URL source) {
        this.fileName = fileName;
        this.source = source;
    }

    /**
     * Resolves an absolute classpath resource, retaining its original filename. An absent resource is allowed
     * so the loader can fall back to OS libraries.
     *
     * @param anchor
     *            class whose resource loader is used
     * @param resource
     *            absolute classpath resource
     * @return a resource descriptor, even if the classpath resource is absent
     */
    public static NativeResource fromClasspath(Class<?> anchor, String resource) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resource, "resource");
        if (!resource.startsWith("/") || resource.endsWith("/"))
            throw new IllegalArgumentException("An absolute resource filename is required: " + resource);
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        if (name.equals(".") || name.equals("..") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0)
            throw new IllegalArgumentException("Invalid native filename: " + name);
        URL url = anchor.getResource(resource);
        return new NativeResource(name, url);
    }

    /**
     * Returns the filename used inside the private extraction directory.
     *
     * @return the extraction filename
     */
    public String fileName() {
        return fileName;
    }

    /**
     * Returns the name passed to System.loadLibrary, removing this platform's library prefix and suffix when
     * present. Use System.mapLibraryName when constructing the classpath resource name.
     *
     * @return the logical OS library name
     */
    public String libraryName() {
        String marker = "nativebootstrap";
        String mapped = System.mapLibraryName(marker);
        int start = mapped.indexOf(marker);
        String prefix = mapped.substring(0, start);
        String suffix = mapped.substring(start + marker.length());
        if (!suffix.isEmpty() && fileName.endsWith(suffix) && fileName.length() > suffix.length()) {
            String name = fileName.substring(0, fileName.length() - suffix.length());
            if (name.startsWith(prefix) && name.length() > prefix.length())
                return name.substring(prefix.length());
            return name;
        }
        return fileName;
    }

    boolean available() {
        return source != null;
    }

    InputStream open() throws IOException {
        if (source == null) throw new FileNotFoundException("Native resource not found: " + fileName);
        return source.openStream();
    }
}
