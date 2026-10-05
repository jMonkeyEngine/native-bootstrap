package com.jme3.nativebootstrap.loader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Objects;

/** A classpath native binary, resolved before any extraction is attempted. */
public final class NativeResource {
    private final String fileName;
    private final URL source;

    private NativeResource(String fileName, URL source) {
        this.fileName = fileName;
        this.source = source;
    }

    /**
     * Resolves an absolute classpath resource, retaining its original filename.
     *
     * @param anchor
     *            class whose resource loader is used
     * @param resource
     *            absolute classpath resource
     * @return a resolved resource
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
        if (url == null) throw new IllegalArgumentException("Native resource not found: " + resource);
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

    InputStream open() throws IOException {
        return source.openStream();
    }
}
