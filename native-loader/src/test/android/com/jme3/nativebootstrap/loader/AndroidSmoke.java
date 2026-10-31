package com.jme3.nativebootstrap.loader;

import com.jme3.nativebootstrap.common.OperatingSystem;
import com.jme3.nativebootstrap.directories.NativeDirectories;
import com.jme3.nativebootstrap.os.OperatingSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Executed by Android's runtime on an emulator, with a real JNI library. */
public final class AndroidSmoke {
    private static native int answer();

    public static void main(String[] args) {
        if (OperatingSystems.detect() != OperatingSystem.ANDROID)
            throw new AssertionError("Android runtime not detected: " + OperatingSystems.detect());
        Path extraction = Paths.get(args[0]);
        System.setProperty("natives.tempDir", extraction.toString());
        System.setProperty("natives.withOsLibraries", "false");
        NativeResource resource = NativeResource.fromClasspath(AndroidSmoke.class,
                "/native/" + System.mapLibraryName("nativebootstrap_probe"));
        if (!resource.available()) throw new AssertionError("Classpath native fixture missing");
        Path loadedFrom = new NativeLoader(path -> System.load(path.toString())).load(
                NativeDirectories.candidates("native-bootstrap-smoke", OperatingSystems::detect),
                Arrays.asList(resource));
        if (loadedFrom != null || Files.exists(extraction) || answer() != 42)
            throw new AssertionError("Android native loading failed or attempted extraction");
        System.out.println("ANDROID_NATIVE_OK 42");
    }
}
