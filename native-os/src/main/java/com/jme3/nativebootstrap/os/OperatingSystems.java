package com.jme3.nativebootstrap.os;

import com.jme3.nativebootstrap.common.OperatingSystem;
import java.util.Locale;

/** Optional operating system detection; pass OperatingSystems::detect as a supplier. */
public final class OperatingSystems {
    private OperatingSystems() {
    }

    /**
     * Reads JVM properties on each invocation, without caching or native code.
     *
     * @return the detected system, or UNKNOWN if property access is denied
     */
    public static OperatingSystem detect() {
        try {
            return classify(System.getProperty("os.name", ""), System.getProperty("java.runtime.name", ""),
                    System.getProperty("java.vm.name", ""));
        } catch (SecurityException denied) {
            return OperatingSystem.UNKNOWN;
        }
    }

    /**
     * Classifies supplied JVM labels, independently of the current host.
     *
     * @param osName
     *            JVM operating system name
     * @param runtimeName
     *            JVM runtime name (used to distinguish Android from Linux)
     * @param vmName
     *            JVM virtual machine name
     * @return the matching enum value, or UNKNOWN
     */
    public static OperatingSystem classify(String osName, String runtimeName, String vmName) {
        String os = normalize(osName);
        String runtime = normalize(runtimeName);
        String vm = normalize(vmName);
        if (runtime.contains("android") || vm.contains("dalvik") || vm.equals("art") || os.equals("android"))
            return OperatingSystem.ANDROID;
        if (os.equals("ios") || os.contains("iphone") || os.contains("ipad")) return OperatingSystem.IOS;
        // Darwin contains "win", so a substring Windows check misclassifies the macOS alias.
        if (os.startsWith("win")) return OperatingSystem.WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return OperatingSystem.MACOS;
        if (os.equals("linux")) return OperatingSystem.LINUX;
        if (os.equals("freebsd")) return OperatingSystem.FREEBSD;
        if (os.equals("openbsd")) return OperatingSystem.OPENBSD;
        if (os.equals("netbsd")) return OperatingSystem.NETBSD;
        if (os.equals("sunos") || os.equals("solaris")) return OperatingSystem.SOLARIS;
        return OperatingSystem.UNKNOWN;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
